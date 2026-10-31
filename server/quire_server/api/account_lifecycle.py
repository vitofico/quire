"""Generic account export and service-scoped closure/purge, with shared caches preserved."""

import hmac
import re
from datetime import UTC, datetime

from fastapi import APIRouter, HTTPException, Request, Response
from sqlalchemy import select, text

from quire_server.db.models import NativeUser

router = APIRouter()

PRIVATE = {
    "highlights": "user_id",
    "highlight_sync_state": "user_id",
    "documents": "user_id",
    "library_items": "user_id",
    "user_ai_preferences": "user_id",
    "ai_usage_daily": "user_id",
    "ai_usage_monthly": "user_id",
    "insight_identity_aliases": "user_id",
    "ai_generation_log": "subject",
    "reader_profiles": "subject",
}


def account_number(account_id):
    if not re.fullmatch(r"native:[1-9][0-9]{0,17}", account_id):
        raise HTTPException(400, "invalid_account")
    return int(account_id.split(":")[1])


def service(request):
    token = request.app.state.identity_settings.service_token
    if not token:
        raise HTTPException(404, "account_service_disabled")
    if not hmac.compare_digest(request.headers.get("authorization", ""), "Bearer " + token):
        raise HTTPException(403, "forbidden")


async def exists(session, table):
    return await session.scalar(text("SELECT to_regclass(:name)"), {"name": table}) is not None


@router.get("/auth/v1/account/export")
async def export(request: Request, response: Response):
    response.headers["Cache-Control"] = "no-store"
    user = await request.app.state.auth_backend.current_user(request)
    data = {
        "version": 1,
        "account_id": user.user_id,
        "exported_at": datetime.now(UTC),
        "epub_files": "not_stored",
    }
    async with request.app.state.identity_factory() as session:
        native = await session.get(NativeUser, account_number(user.user_id))
        data["identity"] = {"email": native.email, "created_at": native.created_at}
        for table, key in PRIVATE.items():
            if await exists(session, table):
                rows = (
                    (
                        await session.execute(
                            text(f"SELECT * FROM {table} WHERE {key}=:id"), {"id": user.user_id}
                        )
                    )
                    .mappings()
                    .all()
                )
                data[table] = [dict(row) for row in rows]
        if await exists(session, "progress"):
            data["progress"] = [
                dict(row)
                for row in (
                    await session.execute(
                        text(
                            "SELECT progress.* FROM progress JOIN documents "
                            "ON documents.pk=progress.document_pk "
                            "WHERE documents.user_id=:id"
                        ),
                        {"id": user.user_id},
                    )
                ).mappings()
            ]
        data["external_identities"] = [
            dict(row)
            for row in (
                await session.execute(
                    text(
                        "SELECT issuer,subject,email,email_verified FROM external_identities "
                        "WHERE user_id=:id"
                    ),
                    {"id": native.id},
                )
            ).mappings()
        ]
    return data


@router.get("/internal/v1/accounts/{account_id}/identity")
async def identity(account_id: str, request: Request):
    service(request)
    number = account_number(account_id)
    async with request.app.state.identity_factory() as session:
        rows = (
            (
                await session.execute(
                    text("SELECT issuer,subject FROM external_identities WHERE user_id=:id"),
                    {"id": number},
                )
            )
            .mappings()
            .all()
        )
    return {"account_id": account_id, "identities": [dict(row) for row in rows]}


@router.post("/internal/v1/accounts/{account_id}/close")
async def close(account_id: str, request: Request):
    service(request)
    number = account_number(account_id)
    async with request.app.state.identity_factory() as session:
        native = (
            await session.execute(
                select(NativeUser).where(NativeUser.id == number).with_for_update()
            )
        ).scalar_one_or_none()
        if native is None:
            return {"account_id": account_id, "status": "absent"}
        native.closed_at = native.closed_at or datetime.now(UTC)
        await session.execute(
            text(
                "UPDATE native_sessions SET revoked_at=COALESCE(revoked_at,now()) WHERE user_id=:id"
            ),
            {"id": number},
        )
        await session.execute(text("DELETE FROM pairing_tickets WHERE user_id=:id"), {"id": number})
        await session.commit()
    return {"account_id": account_id, "status": "closed"}


@router.post("/internal/v1/accounts/{account_id}/purge")
async def purge(account_id: str, request: Request):
    service(request)
    number = account_number(account_id)
    async with request.app.state.identity_factory() as session:
        native = (
            await session.execute(
                select(NativeUser).where(NativeUser.id == number).with_for_update()
            )
        ).scalar_one_or_none()
        if native is None:
            return {"account_id": account_id, "status": "absent"}
        if native.closed_at is None:
            raise HTTPException(409, "account_must_be_closed")
        # Never delete shared book_insights, book_themes or global aliases.
        for table, key in PRIVATE.items():
            if await exists(session, table):
                await session.execute(
                    text(f"DELETE FROM {table} WHERE {key}=:id"), {"id": account_id}
                )
        if await exists(session, "book_insights"):
            await session.execute(
                text("UPDATE book_insights SET generated_by='deleted' WHERE generated_by=:id"),
                {"id": account_id},
            )
        await session.execute(text("DELETE FROM native_sessions WHERE user_id=:id"), {"id": number})
        native.email = f"deleted+{number}@identity.invalid"
        native.password_hash = "closed-account"
        native.purged_at = native.purged_at or datetime.now(UTC)
        await session.commit()
    # Minimal closed identity tombstone prevents an undeleted IdP identity from
    # silently recreating an account while its external deletion is pending.
    return {"account_id": account_id, "status": "purged", "closed_identity_tombstone": True}


@router.post("/internal/v1/accounts/{account_id}/finalize")
async def finalize(account_id: str, request: Request):
    service(request)
    number = account_number(account_id)
    async with request.app.state.identity_factory() as session:
        native = (
            await session.execute(
                select(NativeUser).where(NativeUser.id == number).with_for_update()
            )
        ).scalar_one_or_none()
        if native is not None:
            if native.purged_at is None:
                raise HTTPException(409, "purge_required")
            await session.delete(native)
            await session.commit()
    return {"account_id": account_id, "status": "deleted"}
