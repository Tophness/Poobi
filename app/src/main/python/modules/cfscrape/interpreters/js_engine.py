"""
js_engine.py — Pure Python JavaScript Interpreter
Handles the subset of JS used in Cloudflare IUAM challenges.
Zero external dependencies.
"""
from __future__ import annotations
import re
import math
import json
from typing import Any, Dict, List, Optional


# ─────────────────────────────────────────────
# JS primitive singletons
# ─────────────────────────────────────────────

class _JSUndefined:
    _inst = None
    def __new__(cls):
        if not cls._inst:
            cls._inst = super().__new__(cls)
        return cls._inst
    def __repr__(self):  return 'undefined'
    def __str__(self):   return 'undefined'
    def __bool__(self):  return False


class _JSNull:
    _inst = None
    def __new__(cls):
        if not cls._inst:
            cls._inst = super().__new__(cls)
        return cls._inst
    def __repr__(self):  return 'null'
    def __str__(self):   return 'null'
    def __bool__(self):  return False


undefined = _JSUndefined()
null      = _JSNull()


# ─────────────────────────────────────────────
# Type coercion helpers (ES spec abstractions)
# ─────────────────────────────────────────────

def to_number(v: Any) -> float:
    if isinstance(v, bool):             return 1.0 if v else 0.0
    if isinstance(v, (int, float)) and not isinstance(v, bool): return float(v)
    if isinstance(v, _JSNull):          return 0.0
    if isinstance(v, _JSUndefined):     return float('nan')
    if isinstance(v, str):
        s = v.strip()
        if s == '': return 0.0
        try:
            if re.fullmatch(r'0[xX][0-9a-fA-F]+', s): return float(int(s, 16))
            return float(s)
        except ValueError:
            return float('nan')
    if isinstance(v, list):
        if len(v) == 0: return 0.0
        if len(v) == 1: return to_number(to_string(v[0]))
        return float('nan')
    return float('nan')


def to_string(v: Any) -> str:
    if isinstance(v, bool):         return 'true' if v else 'false'
    if isinstance(v, _JSNull):      return 'null'
    if isinstance(v, _JSUndefined): return 'undefined'
    if isinstance(v, float):
        if v != v:             return 'NaN'
        if v == float('inf'):  return 'Infinity'
        if v == float('-inf'): return '-Infinity'
        if v == int(v):        return str(int(v))
        return str(v)
    if isinstance(v, int) and not isinstance(v, bool): return str(v)
    if isinstance(v, str):    return v
    if isinstance(v, JSArray):
        return ','.join(to_string(x) for x in v)
    if isinstance(v, JSObject): return '[object Object]'
    return str(v)


def to_boolean(v: Any) -> bool:
    if isinstance(v, bool):         return v
    if isinstance(v, _JSNull):      return False
    if isinstance(v, _JSUndefined): return False
    if isinstance(v, float):        return v == v and v != 0.0
    if isinstance(v, int) and not isinstance(v, bool): return v != 0
    if isinstance(v, str):          return len(v) > 0
    return True   # objects / arrays


def to_int32(v: Any) -> int:
    n = to_number(v)
    if n != n or n == 0 or abs(n) == float('inf'): return 0
    n = int(n) & 0xFFFFFFFF
    return n - 0x100000000 if n >= 0x80000000 else n


def to_uint32(v: Any) -> int:
    n = to_number(v)
    if n != n or n == 0 or abs(n) == float('inf'): return 0
    return int(n) & 0xFFFFFFFF


def js_typeof(v: Any) -> str:
    if isinstance(v, _JSUndefined): return 'undefined'
    if isinstance(v, _JSNull):      return 'object'
    if isinstance(v, bool):         return 'boolean'
    if isinstance(v, (int, float)) and not isinstance(v, bool): return 'number'
    if isinstance(v, str):          return 'string'
    if callable(v):                 return 'function'
    return 'object'


def js_add(a: Any, b: Any) -> Any:
    # toPrimitive: arrays / objects → string
    ap = to_string(a) if isinstance(a, (JSArray, JSObject)) else a
    bp = to_string(b) if isinstance(b, (JSArray, JSObject)) else b
    if isinstance(ap, str) or isinstance(bp, str):
        return to_string(ap) + to_string(bp)
    return to_number(ap) + to_number(bp)


def js_eq(a: Any, b: Any) -> bool:
    """Abstract equality (==)"""
    ta, tb = type(a), type(b)
    if ta == tb or (isinstance(a, (int, float)) and isinstance(b, (int, float))
                    and not isinstance(a, bool) and not isinstance(b, bool)):
        if isinstance(a, _JSUndefined): return True
        if isinstance(a, _JSNull):      return True
        if isinstance(a, float) and a != a: return False
        return a == b
    if isinstance(a, (_JSNull, _JSUndefined)) and isinstance(b, (_JSNull, _JSUndefined)):
        return True
    if isinstance(a, (int, float)) and isinstance(b, str):  return a == to_number(b)
    if isinstance(b, (int, float)) and isinstance(a, str):  return b == to_number(a)
    if isinstance(a, bool): return js_eq(to_number(a), b)
    if isinstance(b, bool): return js_eq(a, to_number(b))
    return False


# ─────────────────────────────────────────────
# JS Array / Object wrappers
# ─────────────────────────────────────────────

class JSArray(list):
    def get(self, key: Any) -> Any:
        k = to_string(key)
        if k == 'length': return len(self)
        try:
            idx = int(float(k))
            if 0 <= idx < len(self): return self[idx]
        except (ValueError, OverflowError):
            pass
        return self._method(k)

    def set(self, key: Any, value: Any):
        try:
            idx = int(float(to_string(key)))
            while len(self) <= idx: self.append(undefined)
            self[idx] = value
        except (ValueError, OverflowError):
            pass

    def _method(self, name: str) -> Any:
        a = self
        m: Dict[str, Any] = {
            'join':      lambda sep=',': to_string(sep).join(to_string(x) for x in a),
            'push':      lambda *args: [a.append(x) for x in args] and len(a),
            'pop':       lambda: a.pop() if a else undefined,
            'shift':     lambda: a.pop(0) if a else undefined,
            'unshift':   lambda *args: [a.insert(i, x) for i, x in enumerate(args)] and len(a),
            'slice':     lambda s=0, e=None: JSArray(a[s:e]),
            'splice':    lambda *args: _splice(a, *args),
            'reverse':   lambda: a.reverse() or a,
            'sort':      lambda fn=None: a.sort(key=lambda x: to_string(x)) or a,
            'indexOf':   lambda v, f=0: a.index(v, f) if v in a[f:] else -1,
            'includes':  lambda v, f=0: v in a[int(float(to_string(f))):],
            'concat':    lambda *args: JSArray(list(a) + [x for arg in args for x in (arg if isinstance(arg, list) else [arg])]),
            'forEach':   lambda fn: [fn(x, i, a) for i, x in enumerate(a)] and undefined,
            'map':       lambda fn: JSArray([fn(x, i, a) for i, x in enumerate(a)]),
            'filter':    lambda fn: JSArray([x for i, x in enumerate(a) if to_boolean(fn(x, i, a))]),
            'reduce':    lambda fn, *init: _reduce(a, fn, init[0] if init else undefined),
            'some':      lambda fn: any(to_boolean(fn(x, i, a)) for i, x in enumerate(a)),
            'every':     lambda fn: all(to_boolean(fn(x, i, a)) for i, x in enumerate(a)),
            'find':      lambda fn: next((x for i, x in enumerate(a) if to_boolean(fn(x, i, a))), undefined),
            'findIndex': lambda fn: next((i for i, x in enumerate(a) if to_boolean(fn(x, i, a))), -1),
            'flat':      lambda d=1: JSArray(_flatten(a, d)),
            'toString':  lambda: to_string(a),
            'valueOf':   lambda: a,
        }
        return m.get(name, undefined)

    def __str__(self):  return to_string(self)
    def __repr__(self): return f'[{", ".join(repr(x) for x in self)}]'


class JSObject(dict):
    def get(self, key: Any, *_) -> Any:          # type: ignore[override]
        return self.get_prop(to_string(key))

    def get_prop(self, key: str) -> Any:
        return self[key] if key in self else undefined

    def set_prop(self, key: str, value: Any):
        self[key] = value

    def __str__(self):  return '[object Object]'
    def __repr__(self): return '{' + ', '.join(f'{k}: {repr(v)}' for k, v in self.items()) + '}'


def _reduce(arr, fn, init):
    acc = init
    for i, x in enumerate(arr):
        if isinstance(acc, _JSUndefined):
            acc = x
        else:
            acc = fn(acc, x, i, arr)
    return acc


def _flatten(arr, depth):
    out = []
    for x in arr:
        if isinstance(x, list) and depth > 0:
            out.extend(_flatten(x, depth - 1))
        else:
            out.append(x)
    return out


def _splice(arr, start, delete_count=None, *items):
    s = int(to_number(start))
    if s < 0: s = max(len(arr) + s, 0)
    dc = len(arr) - s if delete_count is None else max(0, int(to_number(delete_count)))
    removed = JSArray(arr[s:s + dc])
    arr[s:s + dc] = list(items)
    return removed


def _parse_int(s, base=10):
    s = to_string(s).strip()
    base = int(to_number(base)) if base != 10 else 10
    if not s: return float('nan')
    neg = s[0] == '-'
    if s[0] in '+-': s = s[1:]
    if base == 16 and s[:2].lower() == '0x': s = s[2:]
    chars = '0123456789abcdefghijklmnopqrstuvwxyz'[:base]
    valid = ''
    for c in s.lower():
        if c in chars: valid += c
        else: break
    if not valid: return float('nan')
    return -int(valid, base) if neg else int(valid, base)


# ─────────────────────────────────────────────
# Tokenizer
# ─────────────────────────────────────────────

_TOK = re.compile(
    r'(?P<COMMENT>//[^\n]*|/\*[\s\S]*?\*/)'
    r'|(?P<WS>\s+)'
    r'|(?P<NUM>0[xX][0-9a-fA-F]+|0[oO][0-7]+|0[bB][01]+'
              r'|\d+\.?\d*(?:[eE][+-]?\d+)?|\.\d+(?:[eE][+-]?\d+)?)'
    r'|(?P<STR>"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\')'
    r'|(?P<TPL>`[^`]*`)'
    r'|(?P<PUNCT>===|!==|==|!=|<=|>=|&&|\|\||>>>=|>>>|>>=|<<=|[-+*/%<>&|^]=|[-+]{2}|=>|\*\*|\.{3}'
               r'|[+\-*/%<>&|^~!?:.,;=\[\](){}])'
    r'|(?P<ID>[a-zA-Z_$][a-zA-Z0-9_$]*)'
)

_ESC = {'n': '\n', 't': '\t', 'r': '\r', '\\': '\\', "'": "'", '"': '"', '0': '\0'}


def _unescape(s: str) -> str:
    out, i = [], 0
    while i < len(s):
        if s[i] == '\\' and i + 1 < len(s):
            c = s[i + 1]
            if c == 'u' and i + 5 < len(s):
                out.append(chr(int(s[i+2:i+6], 16))); i += 6; continue
            out.append(_ESC.get(c, c)); i += 2
        else:
            out.append(s[i]); i += 1
    return ''.join(out)


class Token:
    __slots__ = ('kind', 'val')
    def __init__(self, kind: str, val: Any):
        self.kind, self.val = kind, val
    def __repr__(self): return f'<{self.kind} {self.val!r}>'


class Lexer:
    def __init__(self, src: str):
        self._t: List[Token] = []
        pos = 0
        while pos < len(src):
            m = _TOK.match(src, pos)
            if not m:
                pos += 1; continue
            pos = m.end()
            g = m.lastgroup
            v = m.group()
            if g in ('COMMENT', 'WS'): continue
            if g == 'NUM':
                if re.match(r'0[xX]', v):     self._t.append(Token('NUM', int(v, 16)))
                elif re.match(r'0[oO]', v):    self._t.append(Token('NUM', int(v, 8)))
                elif re.match(r'0[bB]', v):    self._t.append(Token('NUM', int(v, 2)))
                elif '.' in v or 'e' in v.lower(): self._t.append(Token('NUM', float(v)))
                else:                          self._t.append(Token('NUM', int(v)))
            elif g == 'STR':
                self._t.append(Token('STR', _unescape(v[1:-1])))
            elif g == 'TPL':
                self._t.append(Token('STR', v[1:-1]))
            elif g == 'ID':
                self._t.append(Token('ID', v))
            elif g == 'PUNCT':
                self._t.append(Token('P', v))
        self._t.append(Token('EOF', None))
        self.i = 0

    def peek(self, n=0) -> Token:
        idx = self.i + n
        return self._t[idx] if idx < len(self._t) else self._t[-1]

    def next(self) -> Token:
        t = self.peek(); self.i += 1; return t

    def eat(self, v: str) -> Token:
        t = self.next()
        if t.val != v: raise SyntaxError(f'Expected {v!r}, got {t.val!r}')
        return t

    def maybe(self, *vs) -> bool:
        return self.peek().val in vs

    def eof(self) -> bool:
        return self.peek().kind == 'EOF'


# ─────────────────────────────────────────────
# Parser → AST (plain dicts)
# ─────────────────────────────────────────────

class Parser:
    def __init__(self, src: str):
        self.l = Lexer(src)

    def parse(self):
        body = []
        while not self.l.eof():
            s = self._stmt()
            if s: body.append(s)
        return {'T': 'Prog', 'body': body}

    # ── statements ──────────────────────────

    def _stmt(self):
        t = self.l.peek()
        if t.val == ';':                        self.l.next(); return None
        if t.val in ('var', 'let', 'const'):    return self._var()
        if t.val == 'function':                 return self._func()
        if t.val == 'return':                   return self._return()
        if t.val == 'if':                       return self._if()
        if t.val == 'while':                    return self._while()
        if t.val == 'for':                      return self._for()
        if t.val == 'do':                       return self._dowhile()
        if t.val == 'throw':                    return self._throw()
        if t.val == 'try':                      return self._try()
        if t.val == 'switch':                   return self._switch()
        if t.val == 'break':                    self.l.next(); self._semi(); return {'T': 'Break'}
        if t.val == 'continue':                 self.l.next(); self._semi(); return {'T': 'Cont'}
        if t.val == '{':                        return self._block()
        e = self._expr()
        self._semi()
        return {'T': 'Expr', 'e': e}

    def _semi(self):
        if self.l.maybe(';'): self.l.next()

    def _block(self):
        self.l.eat('{')
        body = []
        while not self.l.maybe('}') and not self.l.eof():
            s = self._stmt()
            if s: body.append(s)
        self.l.eat('}')
        return {'T': 'Block', 'body': body}

    def _var(self):
        kind = self.l.next().val
        decls = []
        while True:
            name = self.l.next().val
            init = None
            if self.l.maybe('='):
                self.l.next(); init = self._asgn()
            decls.append({'n': name, 'v': init})
            if not self.l.maybe(','): break
            self.l.next()
        self._semi()
        return {'T': 'Var', 'decls': decls}

    def _func(self):
        self.l.next()   # 'function'
        name = self.l.next().val if self.l.peek().kind == 'ID' else None
        params = self._params()
        body = self._block()
        return {'T': 'Fn', 'name': name, 'params': params, 'body': body}

    def _params(self):
        self.l.eat('(')
        ps = []
        while not self.l.maybe(')'):
            if self.l.maybe('...'): self.l.next()
            ps.append(self.l.next().val)
            if not self.l.maybe(')'): self.l.eat(',')
        self.l.eat(')')
        return ps

    def _return(self):
        self.l.next()
        val = None
        if not self.l.maybe(';', '}') and not self.l.eof():
            val = self._expr()
        self._semi()
        return {'T': 'Ret', 'v': val}

    def _if(self):
        self.l.next()
        self.l.eat('('); test = self._expr(); self.l.eat(')')
        cons = self._stmt()
        alt = None
        if self.l.peek().val == 'else': self.l.next(); alt = self._stmt()
        return {'T': 'If', 'test': test, 'cons': cons, 'alt': alt}

    def _while(self):
        self.l.next()
        self.l.eat('('); test = self._expr(); self.l.eat(')')
        return {'T': 'While', 'test': test, 'body': self._stmt()}

    def _dowhile(self):
        self.l.next()
        body = self._stmt()
        if self.l.peek().val == 'while': self.l.next()
        self.l.eat('('); test = self._expr(); self.l.eat(')')
        self._semi()
        return {'T': 'DoWhile', 'test': test, 'body': body}

    def _for(self):
        self.l.next(); self.l.eat('(')
        init = None
        if not self.l.maybe(';'):
            if self.l.peek().val in ('var', 'let', 'const'):
                kw = self.l.next().val
                name = self.l.next().val
                if self.l.peek().val in ('in', 'of'):
                    loop = self.l.next().val
                    it = self._expr(); self.l.eat(')')
                    return {'T': 'ForIn' if loop == 'in' else 'ForOf',
                            'var': name, 'it': it, 'body': self._stmt()}
                iv = None
                if self.l.maybe('='): self.l.next(); iv = self._asgn()
                init = {'T': 'Var', 'decls': [{'n': name, 'v': iv}]}
            else:
                init = {'T': 'Expr', 'e': self._expr()}
        self.l.eat(';')
        test = None if self.l.maybe(';') else self._expr()
        self.l.eat(';')
        upd = None if self.l.maybe(')') else self._expr()
        self.l.eat(')')
        return {'T': 'For', 'init': init, 'test': test, 'upd': upd, 'body': self._stmt()}

    def _throw(self):
        self.l.next(); val = self._expr(); self._semi()
        return {'T': 'Throw', 'v': val}

    def _try(self):
        self.l.next()
        blk = self._block()
        handler = fin = None
        if self.l.peek().val == 'catch':
            self.l.next()
            param = None
            if self.l.maybe('('): self.l.next(); param = self.l.next().val; self.l.eat(')')
            handler = {'param': param, 'body': self._block()}
        if self.l.peek().val == 'finally':
            self.l.next(); fin = self._block()
        return {'T': 'Try', 'blk': blk, 'handler': handler, 'fin': fin}

    def _switch(self):
        self.l.next(); self.l.eat('(')
        disc = self._expr(); self.l.eat(')'); self.l.eat('{')
        cases = []
        while not self.l.maybe('}'):
            if self.l.peek().val == 'case':
                self.l.next(); tst = self._expr(); self.l.eat(':')
            elif self.l.peek().val == 'default':
                self.l.next(); self.l.eat(':'); tst = None
            else: break
            cons = []
            while not self.l.maybe('case', 'default', '}') and not self.l.eof():
                s = self._stmt()
                if s: cons.append(s)
            cases.append({'test': tst, 'cons': cons})
        self.l.eat('}')
        return {'T': 'Switch', 'disc': disc, 'cases': cases}

    # ── expressions ─────────────────────────

    def _expr(self):
        left = self._asgn()
        if self.l.maybe(','):
            exprs = [left]
            while self.l.maybe(','):
                self.l.next(); exprs.append(self._asgn())
            return {'T': 'Seq', 'exprs': exprs}
        return left

    def _asgn(self):
        left = self._ternary()
        ASGN = {'=','+=','-=','*=','/=','%=','**=','&=','|=','^=','<<=','>>=','>>>='}
        if self.l.peek().val in ASGN:
            op = self.l.next().val; right = self._asgn()
            return {'T': 'Asgn', 'op': op, 'l': left, 'r': right}
        return left

    def _ternary(self):
        t = self._or()
        if self.l.maybe('?'):
            self.l.next()
            c = self._asgn(); self.l.eat(':'); a = self._asgn()
            return {'T': '?:', 'test': t, 'c': c, 'a': a}
        return t

    def _binop(self, ops, sub):
        left = sub()
        while self.l.peek().val in ops:
            op = self.l.next().val; right = sub()
            left = {'T': 'Bin', 'op': op, 'l': left, 'r': right}
        return left

    def _or(self):   return self._binop({'||'}, self._and)
    def _and(self):  return self._binop({'&&'}, self._bor)
    def _bor(self):  return self._binop({'|'},  self._bxor)
    def _bxor(self): return self._binop({'^'},  self._band)
    def _band(self): return self._binop({'&'},  self._eq)
    def _eq(self):   return self._binop({'==','!=','===','!=='}, self._rel)
    def _rel(self):  return self._binop({'<','>','<=','>=','instanceof','in'}, self._shift)
    def _shift(self):return self._binop({'<<','>>','>>>'}, self._add)
    def _add(self):  return self._binop({'+','-'}, self._mul)
    def _mul(self):  return self._binop({'*','/',  '%'}, self._exp)

    def _exp(self):
        left = self._unary()
        if self.l.maybe('**'):
            op = self.l.next().val; right = self._exp()
            return {'T': 'Bin', 'op': op, 'l': left, 'r': right}
        return left

    def _unary(self):
        t = self.l.peek()
        if t.val in ('!','~','+','-') and t.kind == 'P':
            op = self.l.next().val
            return {'T': 'Un', 'op': op, 'arg': self._unary()}
        if t.val in ('typeof','void','delete'):
            op = self.l.next().val
            return {'T': 'Un', 'op': op, 'arg': self._unary()}
        if t.val in ('++','--'):
            op = self.l.next().val
            return {'T': 'PreUp', 'op': op, 'arg': self._unary()}
        return self._postfix()

    def _postfix(self):
        e = self._call()
        if self.l.peek().val in ('++','--'):
            op = self.l.next().val
            return {'T': 'PostUp', 'op': op, 'arg': e}
        return e

    def _call(self):
        obj = self._primary()
        while True:
            t = self.l.peek()
            if t.val == '.':
                self.l.next(); prop = self.l.next().val
                obj = {'T': 'Mem', 'obj': obj, 'prop': prop, 'c': False}
            elif t.val == '[':
                self.l.next(); prop = self._expr(); self.l.eat(']')
                obj = {'T': 'Mem', 'obj': obj, 'prop': prop, 'c': True}
            elif t.val == '(':
                self.l.next()
                args = []
                while not self.l.maybe(')'):
                    if self.l.maybe('...'): self.l.next()
                    args.append(self._asgn())
                    if not self.l.maybe(')'): self.l.eat(',')
                self.l.eat(')')
                obj = {'T': 'Call', 'fn': obj, 'args': args}
            else:
                break
        return obj

    def _primary(self):
        t = self.l.peek()

        if t.val == 'true':      self.l.next(); return {'T': 'Lit', 'v': True}
        if t.val == 'false':     self.l.next(); return {'T': 'Lit', 'v': False}
        if t.val == 'null':      self.l.next(); return {'T': 'Lit', 'v': null}
        if t.val == 'undefined': self.l.next(); return {'T': 'Lit', 'v': undefined}
        if t.val == 'this':      self.l.next(); return {'T': 'This'}
        if t.kind == 'NUM':      self.l.next(); return {'T': 'Lit', 'v': t.val}
        if t.kind == 'STR':      self.l.next(); return {'T': 'Lit', 'v': t.val}

        if t.kind == 'ID':
            self.l.next()
            if t.val == 'new':
                callee = self._call()
                args = []
                if self.l.maybe('('):
                    self.l.next()
                    while not self.l.maybe(')'):
                        args.append(self._asgn())
                        if not self.l.maybe(')'): self.l.eat(',')
                    self.l.eat(')')
                return {'T': 'New', 'fn': callee, 'args': args}
            if t.val == 'function':
                name = self.l.next().val if self.l.peek().kind == 'ID' else None
                params = self._params(); body = self._block()
                return {'T': 'Fn', 'name': name, 'params': params, 'body': body}
            return {'T': 'Id', 'n': t.val}

        if t.val == '[':
            self.l.next()
            els = []
            while not self.l.maybe(']'):
                if self.l.maybe(','): els.append({'T': 'Lit', 'v': undefined}); self.l.next()
                else:
                    els.append(self._asgn())
                    if not self.l.maybe(']'): self.l.eat(',')
            self.l.eat(']')
            return {'T': 'Arr', 'els': els}

        if t.val == '{':
            self.l.next()
            props = []
            while not self.l.maybe('}'):
                kt = self.l.peek()
                if kt.kind in ('ID','STR','NUM'): self.l.next(); key = kt.val
                elif kt.val == '[': self.l.next(); key = self._asgn(); self.l.eat(']')
                else: self.l.next(); key = kt.val
                if self.l.maybe(':'):
                    self.l.next(); val = self._asgn()
                elif self.l.maybe('('):
                    params = self._params(); body = self._block()
                    val = {'T': 'Fn', 'name': None, 'params': params, 'body': body}
                else:
                    val = {'T': 'Id', 'n': str(key)}
                props.append({'k': key, 'v': val})
                if self.l.maybe(','): self.l.next()
            self.l.eat('}')
            return {'T': 'Obj', 'props': props}

        if t.val == '(':
            self.l.next()
            # Detect arrow function
            saved = self.l.i
            try:
                ps = []
                if not self.l.maybe(')'):
                    while True:
                        ps.append(self.l.next().val)
                        if self.l.maybe(')'): break
                        self.l.eat(',')
                self.l.eat(')')
                if self.l.maybe('=>'):
                    self.l.next()
                    body = self._block() if self.l.maybe('{') else {'T': 'Ret', 'v': self._asgn()}
                    return {'T': 'Fn', 'name': None, 'params': ps, 'body': body}
                self.l.i = saved
            except Exception:
                self.l.i = saved
            e = self._expr(); self.l.eat(')')
            return e

        self.l.next()
        return {'T': 'Lit', 'v': undefined}


# ─────────────────────────────────────────────
# Runtime signals
# ─────────────────────────────────────────────

class _Break(Exception):     pass
class _Continue(Exception):  pass
class _Return(Exception):
    def __init__(self, v): self.v = v
class _Throw(Exception):
    def __init__(self, v): self.v = v


# ─────────────────────────────────────────────
# Scope / Environment
# ─────────────────────────────────────────────

class Env:
    def __init__(self, parent: Optional['Env'] = None):
        self._v: Dict[str, Any] = {}
        self.parent = parent

    def get(self, name: str) -> Any:
        if name in self._v: return self._v[name]
        return self.parent.get(name) if self.parent else undefined

    def set(self, name: str, value: Any):
        e: Optional[Env] = self
        while e:
            if name in e._v: e._v[name] = value; return
            e = e.parent
        self._v[name] = value   # auto-create global

    def define(self, name: str, value: Any):
        self._v[name] = value

    def child(self) -> 'Env':
        return Env(self)


# ─────────────────────────────────────────────
# JS Function wrapper
# ─────────────────────────────────────────────

class JSFunction:
    def __init__(self, params, body, env: Env, name=None):
        self.params, self.body, self.env, self.name = params, body, env, name

    def __call__(self, *args):
        local = self.env.child()
        for i, p in enumerate(self.params):
            local.define(p, args[i] if i < len(args) else undefined)
        interp = Interpreter.__new__(Interpreter)
        interp.env = local
        try:
            interp._run(self.body)
        except _Return as r:
            return r.v
        return undefined


# ─────────────────────────────────────────────
# Interpreter
# ─────────────────────────────────────────────

class Interpreter:
    def __init__(self, env: Optional[Env] = None):
        self.env = env or Env()
        if env is None:
            self._setup_builtins()

    # ── public API ──────────────────────────

    def execute(self, source: str) -> Any:
        ast = Parser(source).parse()
        result = undefined
        for stmt in ast['body']:
            result = self._run(stmt)
        return result

    def eval(self, source: str) -> Any:
        return self.execute(source)

    def define(self, name: str, value: Any):
        self.env.define(name, value)

    def get(self, name: str) -> Any:
        return self.env.get(name)

    # ── built-ins ───────────────────────────

    def _setup_builtins(self):
        e = self.env
        import base64 as _b64, time as _time, random as _random
        from urllib.parse import quote, unquote

        Math = JSObject({
            'PI': math.pi, 'E': math.e, 'LN2': math.log(2), 'LN10': math.log(10),
            'SQRT2': math.sqrt(2), 'LOG2E': math.log2(math.e), 'LOG10E': math.log10(math.e),
            'abs':   lambda x: abs(to_number(x)),
            'floor': lambda x: int(math.floor(to_number(x))),
            'ceil':  lambda x: int(math.ceil(to_number(x))),
            'round': lambda x: int(round(to_number(x))),
            'trunc': lambda x: int(math.trunc(to_number(x))),
            'pow':   lambda x, y: to_number(x) ** to_number(y),
            'sqrt':  lambda x: math.sqrt(max(0, to_number(x))),
            'cbrt':  lambda x: to_number(x) ** (1/3),
            'exp':   lambda x: math.exp(to_number(x)),
            'log':   lambda x: math.log(to_number(x)),
            'log2':  lambda x: math.log2(to_number(x)),
            'log10': lambda x: math.log10(to_number(x)),
            'sin':   lambda x: math.sin(to_number(x)),
            'cos':   lambda x: math.cos(to_number(x)),
            'tan':   lambda x: math.tan(to_number(x)),
            'max':   lambda *a: max(to_number(x) for x in a) if a else float('-inf'),
            'min':   lambda *a: min(to_number(x) for x in a) if a else float('inf'),
            'hypot': lambda *a: math.hypot(*[to_number(x) for x in a]),
            'sign':  lambda x: 0 if to_number(x) == 0 else (1 if to_number(x) > 0 else -1),
            'random': lambda: _random.random(),
        })
        JSON_obj = JSObject({
            'parse':     lambda s: _py_to_js(json.loads(to_string(s))),
            'stringify': lambda v, *_: json.dumps(_js_to_py(v)),
        })
        Number_obj = JSObject({
            'isNaN':     lambda x: isinstance(x, float) and x != x,
            'isFinite':  lambda x: isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x),
            'isInteger': lambda x: isinstance(x, int) or (isinstance(x, float) and x == int(x)),
            'parseInt':  _parse_int,
            'parseFloat':lambda s, *_: to_number(to_string(s)),
            'NaN': float('nan'), 'POSITIVE_INFINITY': float('inf'), 'NEGATIVE_INFINITY': float('-inf'),
            'MAX_SAFE_INTEGER': 2**53 - 1, 'MIN_SAFE_INTEGER': -(2**53 - 1),
        })
        Array_obj = JSObject({
            'isArray': lambda x: isinstance(x, list),
            'from':    lambda x, *_: JSArray(x) if isinstance(x, (list, str)) else JSArray(),
            'of':      lambda *a: JSArray(a),
        })
        String_obj = JSObject({
            'fromCharCode': lambda *a: ''.join(chr(int(to_number(x))) for x in a),
        })
        Object_obj = JSObject({
            'keys':     lambda o: JSArray(list(o.keys())) if isinstance(o, dict) else JSArray(),
            'values':   lambda o: JSArray(list(o.values())) if isinstance(o, dict) else JSArray(),
            'entries':  lambda o: JSArray([JSArray([k, v]) for k, v in o.items()]) if isinstance(o, dict) else JSArray(),
            'assign':   lambda t, *s: [t.update(x) for x in s if isinstance(x, dict)] and t,
            'create':   lambda *_: JSObject(),
            'freeze':   lambda o: o,
            'defineProperty': lambda o, k, d: o.__setitem__(to_string(k), d.get('value', undefined)) if isinstance(o, dict) else None,
        })

        e.define('Math',    Math)
        e.define('JSON',    JSON_obj)
        e.define('Number',  Number_obj)
        e.define('Array',   Array_obj)
        e.define('String',  String_obj)
        e.define('Object',  Object_obj)
        e.define('parseInt',   _parse_int)
        e.define('parseFloat', lambda s, *_: to_number(to_string(s)))
        e.define('isNaN',      lambda x: to_number(x) != to_number(x))
        e.define('isFinite',   lambda x: not math.isinf(to_number(x)) and to_number(x) == to_number(x))
        e.define('NaN',        float('nan'))
        e.define('Infinity',   float('inf'))
        e.define('undefined',  undefined)
        e.define('atob',   lambda s: _b64.b64decode(to_string(s)).decode('utf-8'))
        e.define('btoa',   lambda s: _b64.b64encode(to_string(s).encode()).decode())
        e.define('encodeURIComponent', lambda s: quote(to_string(s), safe=''))
        e.define('decodeURIComponent', lambda s: unquote(to_string(s)))
        e.define('encodeURI',          lambda s: quote(to_string(s), safe=";,/?:@&=+$-_.!~*'()#"))
        e.define('decodeURI',          lambda s: unquote(to_string(s)))
        e.define('console', JSObject({
            'log':   lambda *a: print(*[to_string(x) for x in a]) or undefined,
            'warn':  lambda *a: print('WARN:', *[to_string(x) for x in a]) or undefined,
            'error': lambda *a: print('ERR:', *[to_string(x) for x in a]) or undefined,
        }))
        e.define('Date', JSObject({'now': lambda: int(_time.time() * 1000)}))
        e.define('setTimeout',  lambda fn, *_: fn() if callable(fn) else undefined)
        e.define('clearTimeout', lambda *_: undefined)
        e.define('Error',       lambda msg='': JSObject({'message': msg, 'name': 'Error'}))
        e.define('TypeError',   lambda msg='': JSObject({'message': msg, 'name': 'TypeError'}))
        e.define('RegExp',      lambda *_: JSObject({'test': lambda s: False, 'exec': lambda s: null}))
        e.define('Boolean',     lambda v=False: to_boolean(v))
        # window / global self-reference
        e.define('window', e._v)
        e.define('global', e._v)
        e.define('self',   e._v)

    # ── statement execution ──────────────────

    def _run(self, node) -> Any:
        if node is None: return undefined
        T = node['T']

        if T in ('Prog', 'Block'):
            r = undefined
            for s in node['body']: r = self._run(s)
            return r

        if T == 'Expr':  return self._ev(node['e'])
        if T == 'Ret':   raise _Return(self._ev(node['v']) if node['v'] else undefined)
        if T == 'Break': raise _Break()
        if T == 'Cont':  raise _Continue()
        if T == 'Throw': raise _Throw(self._ev(node['v']))

        if T == 'Var':
            for d in node['decls']:
                self.env.define(d['n'], self._ev(d['v']) if d['v'] else undefined)
            return undefined

        if T == 'Fn':
            fn = JSFunction(node['params'], node['body'], self.env, node.get('name'))
            if node.get('name'): self.env.define(node['name'], fn)
            return fn

        if T == 'If':
            if to_boolean(self._ev(node['test'])):
                return self._run_s(node['cons'])
            return self._run_s(node['alt']) if node['alt'] else undefined

        if T == 'While':
            r, i = undefined, 0
            while i < 200_000 and to_boolean(self._ev(node['test'])):
                try: r = self._run_s(node['body'])
                except _Break:    return r
                except _Continue: pass
                i += 1
            return r

        if T == 'DoWhile':
            r, i = undefined, 0
            while i < 200_000:
                try: r = self._run_s(node['body'])
                except _Break:    return r
                except _Continue: pass
                i += 1
                if not to_boolean(self._ev(node['test'])): break
            return r

        if T == 'For':
            if node['init']: self._run(node['init'])
            r, i = undefined, 0
            while i < 200_000:
                if node['test'] and not to_boolean(self._ev(node['test'])): break
                try: r = self._run_s(node['body'])
                except _Break:    return r
                except _Continue: pass
                if node['upd']: self._ev(node['upd'])
                i += 1
            return r

        if T in ('ForIn', 'ForOf'):
            it = self._ev(node['it'])
            items = list(it.keys()) if (T == 'ForIn' and isinstance(it, dict)) else \
                    [str(i) for i in range(len(it))] if T == 'ForIn' else \
                    list(it) if isinstance(it, (list, str)) else []
            r = undefined
            for item in items:
                self.env.define(node['var'], item)
                try: r = self._run_s(node['body'])
                except _Break:    return r
                except _Continue: pass
            return r

        if T == 'Try':
            try:
                return self._run(node['blk'])
            except (_Throw, Exception) as ex:
                if node['handler']:
                    child = Interpreter(self.env.child())
                    p = node['handler']['param']
                    if p:
                        val = ex.v if isinstance(ex, _Throw) else \
                              JSObject({'message': str(ex), 'name': type(ex).__name__})
                        child.env.define(p, val)
                    return child._run(node['handler']['body'])
            finally:
                if node['fin']: self._run(node['fin'])

        if T == 'Switch':
            disc = self._ev(node['disc']); found = False; r = undefined
            for case in node['cases']:
                if not found:
                    if case['test'] is None or js_eq(disc, self._ev(case['test'])): found = True
                if found:
                    try:
                        for s in case['cons']: r = self._run(s)
                    except _Break: return r
            return r

        # fallthrough: treat as expression
        return self._ev(node)

    def _run_s(self, node) -> Any:
        if node is None: return undefined
        if node['T'] == 'Block':
            r = undefined
            for s in node['body']: r = self._run(s)
            return r
        return self._run(node)

    # ── expression evaluation ────────────────

    def _ev(self, node) -> Any:
        if node is None: return undefined
        T = node['T']

        if T == 'Lit':  return node['v']
        if T == 'This': return self.env._v   # approximate

        if T == 'Id':
            n = node['n']
            if n == 'undefined': return undefined
            if n == 'null':      return null
            if n == 'NaN':       return float('nan')
            if n == 'Infinity':  return float('inf')
            if n == 'true':      return True
            if n == 'false':     return False
            return self.env.get(n)

        if T == 'Arr':
            return JSArray([self._ev(e) for e in node['els']])

        if T == 'Obj':
            obj = JSObject()
            for p in node['props']:
                k = p['k']
                if isinstance(k, dict): k = to_string(self._ev(k))
                else: k = to_string(k)
                obj[k] = self._ev(p['v'])
            return obj

        if T == 'Fn':
            fn = JSFunction(node['params'], node['body'], self.env, node.get('name'))
            if node.get('name'): self.env.define(node['name'], fn)
            return fn

        if T == 'Un':
            op, arg = node['op'], node['arg']
            if op == 'typeof':
                try:    return js_typeof(self._ev(arg))
                except: return 'undefined'
            v = self._ev(arg)
            if op == '!':      return not to_boolean(v)
            if op == '+':      return to_number(v)
            if op == '-':      return -to_number(v)
            if op == '~':      return ~to_int32(v)
            if op == 'void':   return undefined
            if op == 'delete': return True

        if T == 'PreUp':
            op  = node['op']
            val = to_number(self._ev(node['arg']))
            nv  = val + 1 if op == '++' else val - 1
            self._assign(node['arg'], nv); return nv

        if T == 'PostUp':
            op  = node['op']
            val = to_number(self._ev(node['arg']))
            self._assign(node['arg'], val + 1 if op == '++' else val - 1)
            return val

        if T == 'Bin':
            op = node['op']
            if op == '&&':
                l = self._ev(node['l']); return l if not to_boolean(l) else self._ev(node['r'])
            if op == '||':
                l = self._ev(node['l']); return l if to_boolean(l) else self._ev(node['r'])
            l, r = self._ev(node['l']), self._ev(node['r'])
            if op == '+':   return js_add(l, r)
            if op == '-':   return to_number(l) - to_number(r)
            if op == '*':   return to_number(l) * to_number(r)
            if op == '**':  return to_number(l) ** to_number(r)
            if op == '/':
                rn = to_number(r)
                if rn == 0: return float('inf') if to_number(l) > 0 else (float('-inf') if to_number(l) < 0 else float('nan'))
                return to_number(l) / rn
            if op == '%':
                rn = to_number(r)
                if rn == 0: return float('nan')
                return math.fmod(to_number(l), rn)
            if op == '==':  return js_eq(l, r)
            if op == '!=':  return not js_eq(l, r)
            if op == '===': return self._strict_eq(l, r)
            if op == '!==': return not self._strict_eq(l, r)
            if op in ('<', '>', '<=', '>='):
                if isinstance(l, str) and isinstance(r, str):
                    return eval(f'{l!r} {op} {r!r}')
                return eval(f'{to_number(l)} {op} {to_number(r)}')
            if op == '&':   return to_int32(l) & to_int32(r)
            if op == '|':   return to_int32(l) | to_int32(r)
            if op == '^':   return to_int32(l) ^ to_int32(r)
            if op == '<<':  return to_int32(l) << (to_uint32(r) & 0x1f)
            if op == '>>':  return to_int32(l) >> (to_uint32(r) & 0x1f)
            if op == '>>>': return to_uint32(l) >> (to_uint32(r) & 0x1f)
            if op == 'instanceof': return False
            if op == 'in':  return to_string(l) in r if isinstance(r, dict) else False
            return undefined

        if T == '?:':
            return self._ev(node['c']) if to_boolean(self._ev(node['test'])) else self._ev(node['a'])

        if T == 'Asgn':
            op, right = node['op'], self._ev(node['r'])
            if op == '=':
                self._assign(node['l'], right); return right
            lv = self._ev(node['l'])
            if   op == '+=':   result = js_add(lv, right)
            elif op == '-=':   result = to_number(lv) - to_number(right)
            elif op == '*=':   result = to_number(lv) * to_number(right)
            elif op == '/=':   result = to_number(lv) / to_number(right) if to_number(right) else float('nan')
            elif op == '%=':   result = math.fmod(to_number(lv), to_number(right))
            elif op == '**=':  result = to_number(lv) ** to_number(right)
            elif op == '&=':   result = to_int32(lv) & to_int32(right)
            elif op == '|=':   result = to_int32(lv) | to_int32(right)
            elif op == '^=':   result = to_int32(lv) ^ to_int32(right)
            elif op == '<<=':  result = to_int32(lv) << (to_uint32(right) & 0x1f)
            elif op == '>>=':  result = to_int32(lv) >> (to_uint32(right) & 0x1f)
            elif op == '>>>=': result = to_uint32(lv) >> (to_uint32(right) & 0x1f)
            else: result = right
            self._assign(node['l'], result); return result

        if T == 'Mem':
            obj  = self._ev(node['obj'])
            prop = self._ev(node['prop']) if node['c'] else node['prop']
            return self._getprop(obj, prop)

        if T == 'Call':
            fn_node = node['fn']
            args = [self._ev(a) for a in node['args']]
            if fn_node['T'] == 'Mem':
                obj  = self._ev(fn_node['obj'])
                prop = self._ev(fn_node['prop']) if fn_node['c'] else fn_node['prop']
                method = self._getprop(obj, prop)
                return self._call(method, args)
            fn = self._ev(fn_node)
            return self._call(fn, args)

        if T == 'New':
            fn   = self._ev(node['fn'])
            args = [self._ev(a) for a in node['args']]
            if callable(fn):
                try:
                    r = fn(*args)
                    return r if isinstance(r, (JSObject, JSArray)) else JSObject()
                except Exception:
                    return JSObject()
            return JSObject()

        if T == 'Seq':
            r = undefined
            for e in node['exprs']: r = self._ev(e)
            return r

        # statement nodes reached from expression context
        return self._run(node)

    def _call(self, fn, args):
        if not callable(fn): return undefined
        try:
            return fn(*args)
        except (_Throw, _Break, _Continue, _Return):
            raise
        except Exception:
            return undefined

    @staticmethod
    def _strict_eq(a, b) -> bool:
        if type(a) != type(b):
            # int/float same numeric type in JS
            if isinstance(a, (int, float)) and isinstance(b, (int, float)) \
               and not isinstance(a, bool) and not isinstance(b, bool):
                return a == b
            return False
        if isinstance(a, float) and a != a: return False
        return a == b

    def _getprop(self, obj: Any, prop: Any) -> Any:
        key = to_string(prop)
        if isinstance(obj, (_JSUndefined, _JSNull)): return undefined

        if isinstance(obj, str):
            if key == 'length': return len(obj)
            s = obj
            methods = {
                'charAt':        lambda i=0:    s[int(to_number(i))] if 0 <= int(to_number(i)) < len(s) else '',
                'charCodeAt':    lambda i=0:    ord(s[int(to_number(i))]) if 0 <= int(to_number(i)) < len(s) else float('nan'),
                'codePointAt':   lambda i=0:    ord(s[int(to_number(i))]) if 0 <= int(to_number(i)) < len(s) else undefined,
                'indexOf':       lambda sub, start=0: s.find(to_string(sub), int(to_number(start))),
                'lastIndexOf':   lambda sub, end=None: s.rfind(to_string(sub)) if end is None else s.rfind(to_string(sub), 0, int(to_number(end))+1),
                'includes':      lambda sub, start=0: to_string(sub) in s[int(to_number(start)):],
                'startsWith':    lambda sub, start=0: s[int(to_number(start)):].startswith(to_string(sub)),
                'endsWith':      lambda sub, end=None: (s[:int(to_number(end))] if end is not None else s).endswith(to_string(sub)),
                'slice':         lambda a, b=None: s[int(to_number(a)):int(to_number(b)) if b is not None else None],
                'substring':     lambda a, b=None: s[min(int(to_number(a)), int(to_number(b)) if b is not None else len(s)):max(int(to_number(a)), int(to_number(b)) if b is not None else len(s))],
                'substr':        lambda a, ln=None: s[int(to_number(a)):int(to_number(a))+int(to_number(ln))] if ln is not None else s[int(to_number(a)):],
                'toUpperCase':   lambda: s.upper(),
                'toLowerCase':   lambda: s.lower(),
                'toLocaleUpperCase': lambda: s.upper(),
                'toLocaleLowerCase': lambda: s.lower(),
                'trim':          lambda: s.strip(),
                'trimStart':     lambda: s.lstrip(),
                'trimEnd':       lambda: s.rstrip(),
                'split':         lambda sep=undefined, lim=undefined: JSArray((s.split(to_string(sep)) if not isinstance(sep, _JSUndefined) else [s])[:int(to_number(lim)) if not isinstance(lim, _JSUndefined) else None]),
                'replace':       lambda pat, rep: s.replace(to_string(pat), to_string(rep) if not callable(rep) else '', 1),
                'replaceAll':    lambda pat, rep: s.replace(to_string(pat), to_string(rep) if not callable(rep) else ''),
                'repeat':        lambda n: s * int(to_number(n)),
                'padStart':      lambda l, fill=' ': s.rjust(int(to_number(l)), (to_string(fill) or ' ')[0]),
                'padEnd':        lambda l, fill=' ': s.ljust(int(to_number(l)), (to_string(fill) or ' ')[0]),
                'concat':        lambda *a: s + ''.join(to_string(x) for x in a),
                'at':            lambda i: s[int(to_number(i))] if -len(s) <= int(to_number(i)) < len(s) else undefined,
                'toString':      lambda: s,
                'valueOf':       lambda: s,
                'match':         lambda _: null,
                'search':        lambda _: -1,
                'normalize':     lambda *_: s,
            }
            if key in methods: return methods[key]
            try:
                idx = int(key)
                if 0 <= idx < len(s): return s[idx]
            except ValueError:
                pass
            return undefined

        if isinstance(obj, JSArray):    return obj.get(key)
        if isinstance(obj, JSObject):   return obj.get_prop(key)

        if isinstance(obj, (int, float)) and not isinstance(obj, bool):
            v = obj
            m = {
                'toString':       lambda base=10: format(int(v), ['', 'b', '', '', '', '', '', '', 'o', '', '', '', '', '', '', '', 'x'][base]) if base != 10 else (str(int(v)) if v == int(v) else str(v)),
                'toFixed':        lambda d=0: f'{v:.{int(to_number(d))}f}',
                'valueOf':        lambda: v,
                'toLocaleString': lambda *_: str(int(v)) if v == int(v) else str(v),
            }
            return m.get(key, undefined)

        if isinstance(obj, dict):
            return obj.get(key, undefined) if isinstance(obj, JSObject) else obj.get(key, undefined)

        if callable(obj):
            if key == 'call':   return lambda this=undefined, *a: obj(*a)
            if key == 'apply':  return lambda this=undefined, a=None: obj(*(a or []))
            if key == 'bind':   return lambda this=undefined, *a: (lambda *more: obj(*a, *more))
            if key == 'length': return 0
            return undefined

        return undefined

    def _assign(self, node, value: Any):
        if node['T'] == 'Id':
            self.env.set(node['n'], value)
        elif node['T'] == 'Mem':
            obj  = self._ev(node['obj'])
            prop = to_string(self._ev(node['prop']) if node['c'] else node['prop'])
            if isinstance(obj, JSObject): obj[prop] = value
            elif isinstance(obj, JSArray): obj.set(prop, value)
            elif isinstance(obj, dict):   obj[prop] = value


# ─────────────────────────────────────────────
# Helpers for JSON ↔ JS type conversion
# ─────────────────────────────────────────────

def _py_to_js(o):
    if o is None:          return null
    if isinstance(o, dict):  return JSObject({k: _py_to_js(v) for k, v in o.items()})
    if isinstance(o, list):  return JSArray([_py_to_js(x) for x in o])
    return o


def _js_to_py(o):
    if isinstance(o, _JSNull):      return None
    if isinstance(o, _JSUndefined): return None
    if isinstance(o, JSObject):     return {k: _js_to_py(v) for k, v in o.items()}
    if isinstance(o, JSArray):      return [_js_to_py(x) for x in o]
    return o