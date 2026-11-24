"""Move a Calibre reading scope to a native Quire account.

``provision`` is the single atomic create-and-reserve step. An operator reaches
it through ``scripts/migrate_calibre_account.py``. A reader reaches it through
``POST /auth/v1/migrate``, proving ownership with the Calibre login their device
already holds. For a limited window after a reader-initiated migration, their
other devices exchange that same Calibre login for a native device session at
``POST /auth/v1/migrate/claim``.
"""

from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import select, text
from sqlalchemy.exc import IntegrityError

from quire_server.api.account_lifecycle import PRIVATE, TENANT_SCOPED, exists
from quire_server.api.browser_identity import issue
from quire_server.core.auth_backend import lock_calibre_scope
from quire_server.core.native_auth import canonical_email, hash_password
from quire_server.db.models import CalibreAccountBinding, NativeUser

SCOPE_COLUMNS = {**PRIVATE, "book_insights": "generated_by"}

router = APIRouter(tags=["auth"])


class MigrationError(ValueError):
    def __init__(self, message: str, code: str = "invalid_migration") -> None:
        super().__init__(message)
        self.code = code


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
        raise MigrationError("Enter the separately verified native email address.", "invalid_email")
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


async def provision(
    factory, username: str, email: str, password: str | None, *, self_service: bool = False
):
    """Keep the dry run read-only and the account plus reservation atomic.

    ``self_service`` records when the reader migrated themselves, which opens
    the device claim window. Operator migrations leave it closed.
    """
    username, email = validated_names(username, email)
    if password is not None and not 12 <= len(password.encode("utf-8")) <= 1024:
        raise MigrationError(
            "Use a new password between 12 and 1024 UTF-8 bytes.", "invalid_password"
        )
    password_hash = await hash_password(password) if password is not None else None

    async with factory.begin() as session:
        if password_hash is not None:
            await lock_calibre_scope(session, username)

        if await session.get(CalibreAccountBinding, username) is not None:
            raise MigrationError("This Calibre scope is already reserved.", "already_migrated")
        if await session.scalar(select(NativeUser.id).where(NativeUser.email == email)) is not None:
            raise MigrationError("This email already has a native account.", "email_unavailable")

        counts = await scope_counts(session, username)
        if password_hash is None:
            return None, counts

        native = NativeUser(email=email, password_hash=password_hash)
        session.add(native)
        await session.flush()
        if any((await scope_counts(session, f"native:{native.id}")).values()):
            raise MigrationError("The new native account scope already contains data.")
        session.add(
            CalibreAccountBinding(
                calibre_user_id=username,
                native_user_id=native.id,
                self_migrated_at=datetime.now(UTC) if self_service else None,
            )
        )
        return native.id, counts


class ClaimRequest(BaseModel):
    device_name: str = Field("Phone", max_length=80)


class MigrateRequest(ClaimRequest):
    # A browser omits the device name: it signs in afterwards for its own cookie session.
    device_name: str | None = Field(None, max_length=80)
    email: str = Field(..., min_length=1, max_length=320)
    password: str = Field(..., min_length=1, max_length=1024)


async def _calibre_username(request: Request) -> str:
    """Verify the Calibre login against Calibre-Web, under the login rate limit."""
    guard = request.app.state.native_login_guard
    guard.begin(request.client.host if request.client else "unknown")
    try:
        return await request.app.state.auth_backend.calibre_username(request)
    finally:
        guard.inflight -= 1


async def _device_session(request: Request, user_id: int, device_name: str) -> dict:
    backend = request.app.state.auth_backend
    expires_at = datetime.now(UTC) + timedelta(seconds=backend._ttl_s)
    token = await issue(
        request.app.state.identity_factory, user_id, backend._ttl_s, "device", device_name
    )
    async with request.app.state.identity_factory() as session:
        email = await session.scalar(select(NativeUser.email).where(NativeUser.id == user_id))
    return {
        "token": token,
        "token_type": "Bearer",
        "expires_at": expires_at.isoformat(),
        "account_id": f"native:{user_id}",
        "account_subject": email,
    }


@router.post("/migrate")
async def migrate(body: MigrateRequest, request: Request) -> dict:
    """Create the reader's Quire account and hand it their existing reading data."""
    username = await _calibre_username(request)
    try:
        user_id, _ = await provision(
            request.app.state.identity_factory,
            username,
            body.email,
            body.password,
            self_service=True,
        )
    except MigrationError as exc:
        raise HTTPException(409, exc.code) from None
    except IntegrityError:
        raise HTTPException(409, "email_unavailable") from None
    if body.device_name is None:
        return {"account_id": f"native:{user_id}"}
    return await _device_session(request, user_id, body.device_name)


@router.post("/migrate/claim")
async def claim(body: ClaimRequest, request: Request) -> dict:
    """Sign in another of the reader's devices with the Calibre login it still holds."""
    username = await _calibre_username(request)
    async with request.app.state.identity_factory() as session:
        binding = await session.get(CalibreAccountBinding, username)
    if binding is None:
        raise HTTPException(409, "not_migrated")
    window = request.app.state.legacy_claim_window
    if (
        binding.native_user_id is None
        or binding.self_migrated_at is None
        or datetime.now(UTC) >= binding.self_migrated_at + window
    ):
        raise HTTPException(403, "native_login_required")
    return await _device_session(request, binding.native_user_id, body.device_name)
