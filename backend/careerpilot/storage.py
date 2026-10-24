"""StorageService: private object storage.

Stage 1 planned MinIO/S3. Stage 2 stores objects under STORAGE_ROOT on the
local filesystem and serves them only through authenticated API views, which
replace the signed URLs. The interface (put/get/delete) is unchanged.
"""
import uuid
from pathlib import Path

from django.conf import settings


class StorageService:
    def __init__(self, root=None):
        self.root = Path(root or settings.STORAGE_ROOT)

    def _path(self, key):
        path = (self.root / key).resolve()
        if self.root.resolve() not in path.parents:
            raise ValueError("invalid storage key")
        return path

    def new_key(self, user, folder, filename):
        safe = "".join(c if c.isalnum() or c in "._-" else "_" for c in filename)[-80:]
        return f"{user.id}/{folder}/{uuid.uuid4().hex}_{safe}"

    def put(self, key, data):
        path = self._path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return key

    def get(self, key):
        return self._path(key).read_bytes()

    def exists(self, key):
        return self._path(key).exists()

    def delete(self, key):
        path = self._path(key)
        if path.exists():
            path.unlink()


storage = StorageService()
