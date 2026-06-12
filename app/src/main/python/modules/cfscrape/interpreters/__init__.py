import re
import sys
import base64
import abc

from ..exceptions import CloudflareSolveError

if sys.version_info >= (3, 4):
    ABC = abc.ABC  # noqa
else:
    ABC = abc.ABCMeta('ABC', (), {})

# ------------------------------------------------------------------------------- #

interpreters = {}

# ------------------------------------------------------------------------------- #


class JavaScriptInterpreter(ABC):

    # ------------------------------------------------------------------------------- #

    @abc.abstractmethod
    def __init__(self, name):
        interpreters[name] = self

    # ------------------------------------------------------------------------------- #

    @classmethod
    def dynamicImport(cls, name=None):
        return interpreters['native']

    # ------------------------------------------------------------------------------- #

    @abc.abstractmethod
    def eval(self, body, domain):
        pass

    # ------------------------------------------------------------------------------- #

    def solveChallenge(self, body, domain):
        try:
            return '{0:.10f}'.format(float(self.eval(body, domain)))
        except Exception:
            raise CloudflareSolveError(
                'Error trying to solve Cloudflare IUAM Javascript, they may have changed their technique.'
            )


# ------------------------------------------------------------------------------- #
# IUAM challenge extraction patterns
# ------------------------------------------------------------------------------- #

_IUAM_PATTERNS = [
    re.compile(
        r'setTimeout\(function\(\){\s+'
        r'(var s,t,o,p,b,r,e,a,k,i,n,g,f.+?\r?\n[\s\S]+?a\.value\s*=.+?)'
        r'\r?\n',
        re.DOTALL,
    ),
    re.compile(
        r'setTimeout\(function\(\){\s+'
        r'(var (?:s,t,o,p,b,r,e|t,r,a,n,s),a,c,k,e,d.+?\r?\n[\s\S]+?a\.value\s*=.+?)'
        r'\r?\n',
        re.DOTALL,
    ),
]


def _browser_stubs(domain):
    d = domain
    return (
        "var window = {}; var global = window; var self = window;"
        "var location = { href: 'https://" + d + "/', hostname: '" + d + "', protocol: 'https:', pathname: '/' };"
        "var navigator = { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36', platform: 'Win32', language: 'en-US' };"
        "var document = {"
        "    getElementById: function(id) { return { value: 0, innerHTML: '', style: {} }; },"
        "    createElement:  function(tag) { return { firstChild: { href: 'https://" + d + "/' }, style: {} }; }"
        "};"
        "var console = { log: function() {}, warn: function() {}, error: function() {} };"
        "var a = { value: 0 };"
    )


# ------------------------------------------------------------------------------- #
# Native pure-Python interpreter
# ------------------------------------------------------------------------------- #

class _NativeInterpreter(JavaScriptInterpreter):
    """
    Built-in pure-Python JavaScript interpreter backed by js_engine.
    Registered as 'native' — the only interpreter this project uses.
    """

    def __init__(self):
        from .js_engine import Interpreter, to_string as _ts
        self._Interpreter = Interpreter
        self._to_string   = _ts
        interpreters['native'] = self

    # ------------------------------------------------------------------------------- #

    def eval(self, body, domain):
        """Execute JS source in a browser-stub context. Returns raw Python value."""
        ctx = self._Interpreter()
        ctx.define('atob', lambda s: base64.b64decode(self._to_string(s)).decode('utf-8'))
        if domain:
            ctx.execute(_browser_stubs(domain))
        return ctx.eval(body)

    # ------------------------------------------------------------------------------- #

    def solveChallenge(self, body, domain):
        """Extract and solve the IUAM JS block. Returns formatted float string."""
        js_block = None
        for pat in _IUAM_PATTERNS:
            m = pat.search(body)
            if m:
                js_block = m.group(1)
                break

        if not js_block:
            raise CloudflareSolveError(
                'Unable to locate Cloudflare IUAM challenge script in the response body.'
            )

        js_block = re.sub(r'document\.getElementById\([^)]*\)', '{ value: 0 }', js_block)
        js_block = re.sub(
            r'document\.createElement\([^)]*\)',
            "{ firstChild: { href: 'https://" + domain + "/' } }",
            js_block,
        )
        js_block = re.sub(r'\.submit\s*\(\s*\)', '', js_block)

        ctx = self._Interpreter()
        ctx.define('atob', lambda s: base64.b64decode(self._to_string(s)).decode('utf-8'))
        ctx.execute(_browser_stubs(domain))
        ctx.execute(js_block)

        a_obj = ctx.get('a')
        if isinstance(a_obj, dict) and 'value' in a_obj:
            try:
                return '{0:.10f}'.format(
                    float(self._to_string(a_obj['value'])) + len(domain)
                )
            except (ValueError, TypeError):
                pass

        raise CloudflareSolveError(
            "Could not extract 'a.value' from the IUAM challenge context."
        )


# ------------------------------------------------------------------------------- #
# Auto-register at import time
# ------------------------------------------------------------------------------- #

_NativeInterpreter()
