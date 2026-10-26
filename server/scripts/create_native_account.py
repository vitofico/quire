"""Operator-only native account creation; never exposes public signup or logs a password."""

import argparse
import asyncio
import getpass

from quire_server.config import get_settings
from quire_server.core.auth_backend import NativeAuth
from quire_server.db.session import make_engine, make_session_factory


async def create(email, password):
    settings = get_settings()
    if settings.auth_backend != "native":
        raise SystemExit("Select native auth for this operator command.")
    engine = make_engine(settings.database_url)
    try:
        backend = NativeAuth(
            session_factory=make_session_factory(engine),
            session_ttl_s=settings.native_session_ttl_s,
        )
        account_id = await backend.register(email=email, password=password)
        print(f"Created native:{account_id}")
    except Exception:
        raise SystemExit(
            "Account creation failed; check server state and duplicate address."
        ) from None
    finally:
        await engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("email")
    args = parser.parse_args()
    password = getpass.getpass("Account password: ")
    if len(password) < 12:
        raise SystemExit("Use at least 12 characters.")
    if password != getpass.getpass("Repeat password: "):
        raise SystemExit("Passwords differ.")
    asyncio.run(create(args.email, password))


if __name__ == "__main__":
    main()
