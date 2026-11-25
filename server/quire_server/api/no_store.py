"""A route class for APIs whose answers are private to one login."""

from __future__ import annotations

from fastapi import Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from starlette.exceptions import HTTPException as StarletteHTTPException


class NoStoreRoute(APIRoute):
    """Answer every request ``no-store``, and keep submitted values out of 422s.

    FastAPI's default 422 quotes each rejected ``input``, which could be a
    password, a catalog address or anything else the client sent.
    """

    def get_route_handler(self):
        handler = super().get_route_handler()

        async def route(request: Request) -> Response:
            try:
                response = await handler(request)
            except RequestValidationError as exc:
                errors = [
                    {key: error[key] for key in ("type", "loc", "msg")} for error in exc.errors()
                ]
                response = JSONResponse({"detail": errors}, status_code=422)
            except StarletteHTTPException as exc:
                response = JSONResponse(
                    {"detail": exc.detail}, status_code=exc.status_code, headers=exc.headers
                )
            response.headers["Cache-Control"] = "no-store"
            return response

        return route
