from fastapi import APIRouter, HTTPException, status
from sqlalchemy import text

from opds_sync.db.session import session_scope

router = APIRouter(tags=["health"])


@router.get("/healthz")
async def healthz() -> dict:
    return {"status": "ok"}


@router.get("/readyz")
async def readyz() -> dict:
    try:
        async with session_scope() as s:
            await s.execute(text("select 1"))
    except Exception as e:  # noqa: BLE001 — readiness must not leak details
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="db unreachable"
        ) from e
    return {"status": "ready"}
