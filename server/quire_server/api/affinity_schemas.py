from __future__ import annotations

from pydantic import BaseModel, ConfigDict

from quire_server.api.ai_schemas import DocumentIdentity, MetadataBundle


class AffinityRequest(BaseModel):
    # extra="forbid" only guards the top level; the shared nested DocumentIdentity/
    # MetadataBundle (from ai_schemas.py) don't forbid extras and aren't changed here.
    model_config = ConfigDict(extra="forbid")
    identity: DocumentIdentity
    bundle: MetadataBundle


class OwnedInfo(BaseModel):
    in_library: bool
    reading_status: str  # finished|abandoned|in_progress|unread


class AffinityReason(BaseModel):
    kind: str
    polarity: str
    message: str


class AffinityResponse(BaseModel):
    affinity_version: int
    owned: OwnedInfo | None
    score: int | None
    band: str
    reasons: list[AffinityReason]
    generated_at: str
