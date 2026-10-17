import sys, zipfile

SRC, DST, REPL_NAME, REPL_PATH = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

with open(REPL_PATH, 'rb') as f:
    newbytes = f.read()

with zipfile.ZipFile(SRC, 'r') as zin, zipfile.ZipFile(DST, 'w', zipfile.ZIP_DEFLATED) as zout:
    n = 0
    replaced = 0
    for item in zin.infolist():
        if item.filename == REPL_NAME:
            ci = zipfile.ZipInfo(REPL_NAME)
            ci.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(ci, newbytes)
            replaced += 1
            print("REPLACED", REPL_NAME, "bytes=", len(newbytes))
        else:
            data = zin.read(item.filename)
            ci = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            ci.compress_type = item.compress_type
            ci.external_attr = item.external_attr
            ci.internal_attr = item.internal_attr
            ci.create_system = item.create_system
            zout.writestr(ci, data)
            n += 1
    print("copied", n, "entries; replaced", replaced, "; total", n + replaced)

if replaced != 1:
    raise SystemExit("ERROR: expected exactly 1 replacement, got " + str(replaced))
print("OK " + DST)
