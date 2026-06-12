"""
http_inspector.py — HTTP request/response inspector
Pure Python, zero external dependencies.

Captures and formats raw HTTP traffic from `requests` Response objects
into human-readable bytes — useful for debugging Cloudflare bypasses
and general HTTP troubleshooting in Kodi addons.

Usage::

    from http_inspector import inspect_response, inspect_all

    resp = session.get("https://example.com")
    print(inspect_response(resp).decode("utf-8"))
"""

__all__ = ('inspect_response', 'inspect_all')

# HTTP version map (httplib int → bytes)
_HTTP_VERSIONS = {9: b'0.9', 10: b'1.0', 11: b'1.1'}


# ─────────────────────────────────────────────────────────────
# Byte utilities
# ─────────────────────────────────────────────────────────────

def _b(value):
    """Safely coerce any value to bytes (None → b'')."""
    if value is None:       return b''
    if isinstance(value, bytes): return value
    return str(value).encode('utf-8')


def _header_line(name, value):
    return _b(name) + b': ' + _b(value) + b'\r\n'


# ─────────────────────────────────────────────────────────────
# URL helpers
# ─────────────────────────────────────────────────────────────

def _parse(url):
    from urllib.parse import urlparse
    return urlparse(url)


def _request_line_path(url, via_proxy=False, proxy_url=None):
    """
    Returns (path_bytes, parsed_uri).
    When going through a CONNECT proxy the full URL is used as path.
    """
    uri = _parse(url)
    if via_proxy and proxy_url:
        return _b(proxy_url), uri
    path = _b(uri.path) or b'/'
    if uri.query:
        path += b'?' + _b(uri.query)
    return path, uri


# ─────────────────────────────────────────────────────────────
# Proxy detection
# ─────────────────────────────────────────────────────────────

def _detect_proxy(response):
    """Return (via_proxy, method_override, proxy_url)."""
    if not getattr(response.connection, 'proxy_manager', False):
        return False, None, None
    url = response.request.url
    method = 'CONNECT' if url.startswith('https://') else None
    return True, method, url


# ─────────────────────────────────────────────────────────────
# Serialisers
# ─────────────────────────────────────────────────────────────

def _write_request(req, buf, prefix=b'< ', via_proxy=False, method_override=None, proxy_url=None):
    method = _b(method_override or req.method)
    path, uri = _request_line_path(req.url, via_proxy, proxy_url)

    # ── Request line ──────────────────────────────────────────
    buf.extend(prefix + method + b' ' + path + b' HTTP/1.1\r\n')

    # ── Headers ───────────────────────────────────────────────
    headers = dict(req.headers)
    host    = _b(headers.pop('Host', None) or uri.netloc)
    buf.extend(prefix + b'Host: ' + host + b'\r\n')

    for name, value in headers.items():
        buf.extend(prefix + _header_line(name, value))

    buf.extend(prefix + b'\r\n')

    # ── Body ──────────────────────────────────────────────────
    if req.body:
        if isinstance(req.body, (str, bytes)):
            buf.extend(prefix + _b(req.body))
        else:
            buf.extend(b'<< body is a non-string type >>')
        buf.extend(b'\r\n')

    buf.extend(b'\r\n')


def _write_response(response, buf, prefix=b'> '):
    raw     = response.raw
    version = _HTTP_VERSIONS.get(getattr(raw, 'version', 11), b'1.1')
    status  = str(getattr(raw, 'status', response.status_code)).encode('ascii')
    reason  = _b(response.reason)

    # ── Status line ───────────────────────────────────────────
    buf.extend(prefix + b'HTTP/' + version + b' ' + status + b' ' + reason + b'\r\n')

    # ── Headers ───────────────────────────────────────────────
    raw_headers = getattr(raw, 'headers', {})
    for name in raw_headers.keys():
        values = (
            raw_headers.getlist(name)
            if hasattr(raw_headers, 'getlist')
            else [raw_headers[name]]
        )
        for value in values:
            buf.extend(prefix + _header_line(name, value))

    buf.extend(prefix + b'\r\n')
    buf.extend(response.content)


# ─────────────────────────────────────────────────────────────
# Public API
# ─────────────────────────────────────────────────────────────

def inspect_response(response, request_prefix=b'< ', response_prefix=b'> ', buf=None):
    """
    Serialise a single request-response cycle into a :class:`bytearray`.

    :param response:        A :class:`requests.Response` object.
    :param request_prefix:  Bytes prepended to each request line.  Default ``b'< '``
    :param response_prefix: Bytes prepended to each response line. Default ``b'> '``
    :param buf:             Existing :class:`bytearray` to append into (optional).
    :returns:               :class:`bytearray` with formatted HTTP traffic.
    :raises ValueError:     If the response has no associated request.

    Example::

        data = inspect_response(resp)
        print(data.decode('utf-8'))
    """
    if not hasattr(response, 'request'):
        raise ValueError('Response has no associated request.')

    out = buf if buf is not None else bytearray()
    via_proxy, method_override, proxy_url = _detect_proxy(response)

    _write_request(
        response.request, out,
        prefix=_b(request_prefix),
        via_proxy=via_proxy,
        method_override=method_override,
        proxy_url=proxy_url,
    )
    _write_response(response, out, prefix=_b(response_prefix))
    return out


def inspect_all(response, request_prefix=b'< ', response_prefix=b'> '):
    """
    Serialise every hop in the redirect chain plus the final response.

    :param response:        A :class:`requests.Response` object.
    :param request_prefix:  Bytes prepended to each request line.
    :param response_prefix: Bytes prepended to each response line.
    :returns:               :class:`bytearray` with all hops concatenated.

    Example::

        data = inspect_all(resp)
        print(data.decode('utf-8'))
    """
    out = bytearray()
    for r in list(response.history) + [response]:
        inspect_response(r, request_prefix, response_prefix, out)
    return out