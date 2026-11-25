"""Encryption and key setup for synced catalog logins."""

from __future__ import annotations

import base64
import json
import logging
import os
import stat
from uuid import uuid4

import pytest
from sqlalchemy.exc import ProgrammingError

from quire_server.api.admin import MASK, settings_view
from quire_server.config import Settings
from quire_server.core.catalog_secrets import (
    CatalogSecretError,
    CatalogSecrets,
    create_catalog_key,
    load_catalog_secrets,
)

SECRET = {"url": "https://kavita.example/api/opds/abc", "username": None, "password": None}


def _key() -> bytes:
    return os.urandom(32)


def _keys_json(**keys: bytes) -> str:
    return json.dumps({kid: base64.b64encode(key).decode() for kid, key in keys.items()})


def _settings(tmp_path, **values) -> Settings:
    values.setdefault("catalog_key_file", str(tmp_path / "catalog.key"))
    return Settings(_env_file=None, **values)


class _Session:
    """Answers the "do catalog rows exist?" query."""

    def __init__(self, rows_exist: bool) -> None:
        self.rows_exist = rows_exist

    async def scalar(self, _statement):
        return self.rows_exist


@pytest.fixture(autouse=True)
def _no_catalog_env(monkeypatch):
    monkeypatch.delenv("QUIRE_SERVER_CATALOG_KEYS", raising=False)
    monkeypatch.delenv("QUIRE_SERVER_CATALOG_KEY_FILE", raising=False)


# ---------------------------------------------------------------------------
# Encryption
# ---------------------------------------------------------------------------


def test_round_trip_with_a_fresh_nonce_each_time():
    box = CatalogSecrets({"k1": _key()})
    catalog_id = uuid4()

    first = box.encrypt("native:1", catalog_id, SECRET)
    second = box.encrypt("native:1", catalog_id, SECRET)

    assert first.startswith("k1:") and first != second
    assert "kavita" not in first
    assert box.decrypt("native:1", catalog_id, first) == SECRET


@pytest.mark.parametrize("moved", ["user", "id"])
def test_a_row_moved_to_another_user_or_id_does_not_decrypt(moved):
    box = CatalogSecrets({"k1": _key()})
    catalog_id = uuid4()
    sealed = box.encrypt("native:1", catalog_id, SECRET)

    user, target = ("native:2", catalog_id) if moved == "user" else ("native:1", uuid4())
    with pytest.raises(CatalogSecretError):
        box.decrypt(user, target, sealed)


@pytest.mark.parametrize(
    "tamper",
    [
        lambda s: s[:-2] + ("A" if s[-2] != "A" else "B") + s[-1],
        lambda s: s.split(":", 1)[0] + ":" + "short",
        lambda s: s.split(":", 1)[0] + ":" + "not base64 é",
        lambda s: "k9:" + s.split(":", 1)[1],
        lambda s: s.split(":", 1)[1],
    ],
    ids=["flipped byte", "truncated", "garbage", "unknown key id", "no key id"],
)
def test_tampered_ciphertext_does_not_decrypt(tamper):
    box = CatalogSecrets({"k1": _key()})
    catalog_id = uuid4()
    sealed = box.encrypt("native:1", catalog_id, SECRET)

    with pytest.raises(CatalogSecretError):
        box.decrypt("native:1", catalog_id, tamper(sealed))


def test_rotation_new_first_key_encrypts_old_key_still_decrypts():
    old, new = _key(), _key()
    catalog_id = uuid4()
    before = CatalogSecrets({"old": old}).encrypt("native:1", catalog_id, SECRET)

    rotated = CatalogSecrets({"new": new, "old": old})
    after = rotated.encrypt("native:1", catalog_id, SECRET)

    assert after.startswith("new:")
    assert rotated.decrypt("native:1", catalog_id, before) == SECRET
    with pytest.raises(CatalogSecretError):
        CatalogSecrets({"old": old}).decrypt("native:1", catalog_id, after)


# ---------------------------------------------------------------------------
# Where the key comes from
# ---------------------------------------------------------------------------


def test_environment_wins_over_the_key_file(tmp_path, monkeypatch):
    env_key, file_key = _key(), _key()
    (tmp_path / "catalog.key").write_text(_keys_json(file=file_key))
    monkeypatch.setenv("QUIRE_SERVER_CATALOG_KEYS", _keys_json(env=env_key))

    box, source = load_catalog_secrets(_settings(tmp_path))

    assert source == "environment"
    assert box.encrypt("u", uuid4(), SECRET).startswith("env:")


@pytest.mark.parametrize("env_value", [None, "", "  "])
def test_key_file_is_used_when_the_environment_has_none(tmp_path, monkeypatch, env_value):
    (tmp_path / "catalog.key").write_text(_keys_json(file=_key()))
    if env_value is not None:
        monkeypatch.setenv("QUIRE_SERVER_CATALOG_KEYS", env_value)

    box, source = load_catalog_secrets(_settings(tmp_path))

    assert source == "file"
    assert box.encrypt("u", uuid4(), SECRET).startswith("file:")


def test_no_key_anywhere_means_off_and_nothing_is_created(tmp_path):
    assert load_catalog_secrets(_settings(tmp_path)) == (None, "none")
    assert load_catalog_secrets(_settings(tmp_path, catalog_key_file="")) == (None, "none")
    assert list(tmp_path.iterdir()) == []


@pytest.mark.parametrize(
    "raw",
    [
        "not json SECRETVALUE",
        "{}",
        '["SECRETVALUE"]',
        '{"k1": "SECRETVALUE"}',
        json.dumps({"k1": base64.b64encode(b"SECRETVALUE").decode()}),
        json.dumps({"bad:id": base64.b64encode(os.urandom(32)).decode()}),
    ],
)
def test_a_bad_key_map_stops_boot_without_printing_it(tmp_path, monkeypatch, raw):
    monkeypatch.setenv("QUIRE_SERVER_CATALOG_KEYS", raw)

    with pytest.raises(RuntimeError, match="QUIRE_SERVER_CATALOG_KEYS") as error:
        load_catalog_secrets(_settings(tmp_path))
    assert "SECRETVALUE" not in str(error.value)
    # No chained parser error whose message or traceback could quote the value.
    assert error.value.__context__ is None


def test_keys_stay_out_of_repr_and_the_admin_settings(tmp_path, monkeypatch):
    raw = _keys_json(k1=_key())
    monkeypatch.setenv("QUIRE_SERVER_CATALOG_KEYS", raw)
    settings = _settings(tmp_path)
    secret_part = json.loads(raw)["k1"]

    assert secret_part not in repr(settings) and secret_part not in str(settings)
    rows = {row["name"]: row["value"] for row in settings_view(settings)}
    assert rows["QUIRE_SERVER_CATALOG_KEYS"] == MASK
    assert rows["QUIRE_SERVER_CATALOG_KEY_FILE"] == str(tmp_path / "catalog.key")
    assert secret_part not in repr(load_catalog_secrets(settings))


# ---------------------------------------------------------------------------
# Creating the key file on first start
# ---------------------------------------------------------------------------


async def test_key_created_on_first_start_and_reused_on_the_second(tmp_path):
    settings = _settings(tmp_path)
    catalog_id = uuid4()

    created = await create_catalog_key(settings, _Session(rows_exist=False))
    sealed = created.encrypt("native:1", catalog_id, SECRET)
    path = tmp_path / "catalog.key"

    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert [p.name for p in tmp_path.iterdir()] == ["catalog.key"]
    box, source = load_catalog_secrets(settings)
    assert source == "file"
    assert box.decrypt("native:1", catalog_id, sealed) == SECRET


async def test_a_key_file_created_meanwhile_is_kept(tmp_path):
    """Two servers starting together: the one that links second reads the winner's key."""
    path = tmp_path / "catalog.key"
    winner = CatalogSecrets({"winner": (key := _key())})
    path.write_text(_keys_json(winner=key))
    sealed = winner.encrypt("native:1", catalog_id := uuid4(), SECRET)

    box = await create_catalog_key(_settings(tmp_path), _Session(rows_exist=False))

    assert path.read_text() == _keys_json(winner=key)
    assert box.decrypt("native:1", catalog_id, sealed) == SECRET
    assert [p.name for p in tmp_path.iterdir()] == ["catalog.key"]


@pytest.mark.skipif(os.geteuid() == 0, reason="root writes to read-only folders")
async def test_no_key_created_in_a_folder_that_is_not_writable(tmp_path, caplog):
    folder = tmp_path / "keys"
    folder.mkdir(mode=0o500)
    try:
        with caplog.at_level(logging.WARNING):
            box = await create_catalog_key(
                _settings(tmp_path, catalog_key_file=str(folder / "catalog.key")),
                _Session(rows_exist=False),
            )
        assert box is None
        assert list(folder.iterdir()) == []
    finally:
        folder.chmod(0o700)
    assert "Catalog sync is off" in caplog.text
    assert "QUIRE_SERVER_CATALOG_KEY_FILE" in caplog.text


async def test_no_key_created_in_a_missing_folder(tmp_path, caplog):
    settings = _settings(tmp_path, catalog_key_file=str(tmp_path / "absent" / "catalog.key"))

    with caplog.at_level(logging.WARNING):
        assert await create_catalog_key(settings, _Session(rows_exist=False)) is None
    assert not (tmp_path / "absent").exists()
    assert "QUIRE_SERVER_CATALOG_KEY_FILE" in caplog.text


async def test_no_key_created_when_saved_catalogs_need_a_missing_key(tmp_path, caplog):
    with caplog.at_level(logging.WARNING):
        box = await create_catalog_key(_settings(tmp_path), _Session(rows_exist=True))

    assert box is None
    assert list(tmp_path.iterdir()) == []
    assert "saved catalogs exist" in caplog.text


class _Failing:
    """A database that cannot answer: unreachable, or not migrated yet."""

    def __init__(self, error: Exception) -> None:
        self.error = error

    async def scalar(self, _statement):
        raise self.error


@pytest.mark.parametrize(
    "error",
    [
        ConnectionRefusedError("synthetic refusal from db.internal.example"),
        ProgrammingError("SELECT", {}, Exception('relation "account_catalogs" does not exist')),
    ],
)
async def test_a_database_failure_leaves_catalog_sync_off(tmp_path, caplog, error):
    with caplog.at_level(logging.WARNING):
        assert await create_catalog_key(_settings(tmp_path), _Failing(error)) is None

    assert list(tmp_path.iterdir()) == []
    assert caplog.messages == [
        f"Catalog sync is off: creating the catalog key failed ({type(error).__name__})"
    ]


async def test_a_failing_link_leaves_catalog_sync_off(tmp_path, caplog, monkeypatch):
    def refuse(source, target):
        raise PermissionError(13, "Permission denied", str(target))

    monkeypatch.setattr(os, "link", refuse)
    with caplog.at_level(logging.WARNING):
        assert await create_catalog_key(_settings(tmp_path), _Session(rows_exist=False)) is None

    # The temporary file holding the key is gone too.
    assert list(tmp_path.iterdir()) == []
    assert caplog.messages == [
        "Catalog sync is off: creating the catalog key failed (PermissionError)"
    ]
