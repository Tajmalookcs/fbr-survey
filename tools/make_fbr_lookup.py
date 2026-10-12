"""
make_fbr_lookup.py
Builds FBR_Lookup.fbr for the FBR Shop Survey app from db.sqlite3.

* Opens db.sqlite3 READ-ONLY (nothing in the database is changed).
* Copies ONLY: CNIC, taxpayer name and business name.
* CNICs are stored scrambled (hashed) and names are locked with their own CNIC,
  so the file cannot be read back as a list. A name is only revealed when that
  exact CNIC is typed in the app.

Run: double-click MAKE_FBR_LOOKUP.bat (or: python make_fbr_lookup.py)
"""
import hashlib
import os
import re
import sqlite3
import struct
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(HERE, "db.sqlite3")
OUT = os.path.join(HERE, "FBR_Lookup.fbr")

MAGIC = b"FBRLKP3\n"
ID_SALT = b"fbr-survey-id-v1|"
KEY_PREFIX = b"fbr-survey-name-"
MAX_TEXT = 128  # name + newline + business name


def keystream(c):
    return b"".join(hashlib.sha256(KEY_PREFIX + bytes([97 + i]) + b"|" + c).digest() for i in range(4))


def cnic_of(value):
    """Return the 13-digit CNIC in value, or None."""
    d = re.sub(r"\D", "", value or "")
    return d if len(d) == 13 and d[0] in "1234567" else None


def main():
    if not os.path.exists(DB):
        sys.exit("db.sqlite3 not found next to this script.")

    con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
    row = con.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND lower(name) LIKE '%localprofile%'"
    ).fetchone()
    if not row:
        sys.exit("Could not find the LocalProfile table in db.sqlite3.")
    table = row[0]
    print(f"Reading table: {table}")

    total = 0
    found_in = {"registration_no": 0, "ntn": 0}
    names = {}
    t0 = time.time()
    for reg, ntn, nm, biz in con.execute(f'SELECT registration_no, ntn, name, business_name FROM "{table}"'):
        total += 1
        nm = " ".join((nm or "").split()).upper()
        biz = " ".join((biz or "").split()).upper()
        for col, v in (("registration_no", reg), ("ntn", ntn)):
            c = cnic_of(v)
            if c:
                found_in[col] += 1
                old_nm, old_biz = names.get(c, ("", ""))
                names[c] = (old_nm or nm, old_biz or biz)
        if total % 200000 == 0:
            print(f"  {total:,} records read...")
    con.close()

    print()
    print(f"Records in database        : {total:,}")
    print(f"CNICs in Registration No   : {found_in['registration_no']:,}")
    print(f"CNICs in NTN               : {found_in['ntn']:,}")
    print(f"Unique CNICs for the app   : {len(names):,}")
    print(f"  ...with business name     : {sum(1 for v in names.values() if v[1]):,}")
    if not names:
        sys.exit("No 13-digit CNICs found. Nothing written.")

    entries = []
    for c, (nm, biz) in names.items():
        h = struct.unpack(">q", hashlib.sha256(ID_SALT + c.encode()).digest()[:8])[0]
        text = nm[:60] + "\n" + biz
        b = text.encode("utf-8")[:MAX_TEXT].decode("utf-8", "ignore").encode("utf-8")
        ks = keystream(c.encode())
        entries.append((h, bytes(x ^ y for x, y in zip(b, ks))))
    entries.sort(key=lambda e: e[0])

    tmp = OUT + ".tmp"
    with open(tmp, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack(">qq", len(entries), int(time.time())))
        offset = 0
        for h, enc in entries:
            f.write(struct.pack(">qI", h, offset))
            offset += 1 + len(enc)
        for _, enc in entries:
            f.write(bytes([len(enc)]))
            f.write(enc)
    os.replace(tmp, OUT)

    size_mb = os.path.getsize(OUT) / 1024 / 1024
    print()
    print(f"Done in {time.time() - t0:.0f}s")
    print(f"Created: {OUT}  ({size_mb:.1f} MB)")
    print("Send this file to surveyors' phones and use  Menu > Import FBR data  in the app.")


if __name__ == "__main__":
    main()
