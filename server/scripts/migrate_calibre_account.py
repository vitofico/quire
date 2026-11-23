"""Provision a dedicated native account with an operator-verified Calibre scope."""

import argparse
import asyncio
import getpass
import sys

from sqlalchemy import select, text

from quire_server.api.account_lifecycle import PRIVATE, TENANT_SCOPED, exists
from quire_server.config import get_settings
from quire_server.core.auth_backend import lock_calibre_scope
from quire_server.core.native_auth import canonical_email, hash_password
from quire_server.db.models import CalibreAccountBinding, NativeUser
from quire_server.db.session import make_engine, make_session_factory

SCOPE_COLUMNS = {**PRIVATE, "book_insights": "generated_by"}


class MigrationError(ValueError):
    pass


def validated_names(username: str, email: str) -> tuple[str, str]:
    if (
        not username.strip()
        or ":" in username
        or any(ord(char) < 32 or ord(char) == 127 for char in username)
        or len(username.encode("utf-8")) > 256
    ):
        raise MigrationError("Enter a valid Calibre username.")
    email = canonical_email(email)
    if (
        not 1 <= len(email) <= 320
        or email.count("@") != 1
        or any(not part for part in email.split("@"))
        or any(char.isspace() or ord(char) < 32 for char in email)
    ):
        raise MigrationError("Enter the separately verified native email address.")
    return username.lower(), email


async def scope_counts(session, user_id: str) -> dict[str, int]:
    counts = {}
    for table, column in SCOPE_COLUMNS.items():
        if await exists(session, table):
            if table in TENANT_SCOPED and await session.scalar(
                text(f"SELECT 1 FROM {table} WHERE {column}=:id AND tenant_id<>'local' LIMIT 1"),
                {"id": user_id},
            ):
                raise MigrationError(
                    "This scope conflicts with an existing non-local AI principal."
                )
            counts[table] = await session.scalar(
                text(f"SELECT count(*) FROM {table} WHERE {column}=:id"),
                {"id": user_id},
            )
    return counts


async def provision(factory, username: str, email: str, password: str | None):
    """Keep the dry run read-only and the account plus reservation atomic."""
    username, email = validated_names(username, email)
    if password is not None and not 12 <= len(password.encode("utf-8")) <= 1024:
        raise MigrationError("Use a new password between 12 and 1024 UTF-8 bytes.")
    password_hash = await hash_password(password) if password is not None else None

    async with factory.begin() as session:
        if password_hash is not None:
            await lock_calibre_scope(session, username)

        if await session.get(CalibreAccountBinding, username) is not None:
            raise MigrationError("This Calibre scope is already reserved.")
        if await session.scalar(select(NativeUser.id).where(NativeUser.email == email)) is not None:
            raise MigrationError("This email already has a native account.")

        counts = await scope_counts(session, username)
        if password_hash is None:
            return None, counts

        native = NativeUser(email=email, password_hash=password_hash)
        session.add(native)
        await session.flush()
        if any((await scope_counts(session, f"native:{native.id}")).values()):
            raise MigrationError("The new native account scope already contains data.")
        session.add(CalibreAccountBinding(calibre_user_id=username, native_user_id=native.id))
        return native.id, counts


async def run(username: str, email: str, password: str | None):
    settings = get_settings()
    if settings.auth_backend != "transition":
        raise MigrationError("Select transition auth for this operator command.")
    engine = make_engine(settings.database_url)
    try:
        return await provision(make_session_factory(engine), username, email, password)
    finally:
        await engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("calibre_username")
    parser.add_argument("native_email")
    parser.add_argument("--apply", action="store_true", help="Create and reserve the account")
    args = parser.parse_args()
    try:
        username, email = validated_names(args.calibre_username, args.native_email)
        _, counts = asyncio.run(run(username, email, None))
        for table, count in counts.items():
            print(f"{table}: {count}")
        if not args.apply:
            print("Dry run complete. No account or reservation was created.")
            return

        if not sys.stdin.isatty():
            raise MigrationError("Use an interactive terminal for confirmation and password entry.")
        print(f"Verified mapping: Calibre scope {username!r} → native email {email!r}")
        print("Applying immediately disables Basic authentication for this Calibre scope.")
        if (
            input(
                "After verifying both identities with the account owner, type the Calibre scope: "
            )
            != username
        ):
            raise MigrationError("Calibre scope confirmation did not match.")
        password = getpass.getpass("New native password: ")
        if password != getpass.getpass("Repeat new native password: "):
            raise MigrationError("Passwords differ.")
        account_id, _ = asyncio.run(run(username, email, password))
        print(f"Reserved Calibre scope for native:{account_id}.")
    except MigrationError as exc:
        raise SystemExit(str(exc)) from None
    except Exception:
        raise SystemExit(
            "Account migration failed. Check the database and migration state."
        ) from None


if __name__ == "__main__":
    main()
