"""Provision a dedicated native account with an operator-verified Calibre scope."""

import argparse
import asyncio
import getpass
import sys

from quire_server.api.calibre_migration import MigrationError, provision, validated_names
from quire_server.config import get_settings
from quire_server.db.session import make_engine, make_session_factory


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
