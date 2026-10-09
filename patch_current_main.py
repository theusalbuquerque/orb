#!/usr/bin/env python3
"""Add the owner ZDA router to the EXISTING backend main.py without overwriting other routes."""
from pathlib import Path
import ast
import sys

main = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('main.py')
source = main.read_text(encoding='utf-8')
import_anchor = 'from billing_api import router as billing_router\n'
register_anchor = 'app.include_router(billing_router)\n'
import_line = 'from orb_owner_zero_auth_api import router as owner_zda_router\n'
register_line = 'app.include_router(owner_zda_router)\n'
if source.count(import_anchor) != 1 or source.count(register_anchor) != 1:
    raise SystemExit('ABORT: unexpected main.py structure; no file changed')
if import_line not in source:
    source = source.replace(import_anchor, import_anchor + import_line, 1)
if register_line not in source:
    source = source.replace(register_anchor, register_anchor + register_line, 1)
ast.parse(source, filename=str(main))
main.write_text(source, encoding='utf-8')
print('OK: registered ZDA router without replacing other application code')
