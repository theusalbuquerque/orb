"""Check 64-bit ELF segments and uncompressed JNI ZIP offsets in built APKs."""
import glob
import struct
import sys
import zipfile
from pathlib import Path

PAGE = 16384


def check_apk(path):
    errors = []
    count = 0
    with zipfile.ZipFile(path) as apk, open(path, 'rb') as raw:
        for info in apk.infolist():
            if not info.filename.endswith('.so') or not info.filename.startswith(
                ('lib/arm64-v8a/', 'lib/x86_64/')
            ):
                continue
            count += 1
            data = apk.read(info)
            name = f'{path.name}: {info.filename}'
            if data[:6] != b'\x7fELF\x02\x01':
                errors.append(f'{name}: expected little-endian ELF64')
                continue
            offset = struct.unpack_from('<Q', data, 32)[0]
            size, number = struct.unpack_from('<HH', data, 54)
            headers = [struct.unpack_from('<IIQQQQQQ', data, offset + index * size)
                       for index in range(number)]
            loads = 0
            for kind, flags, file_offset, address, _, file_size, mem_size, alignment in headers:
                if kind == 1:
                    loads += 1
                    if alignment < PAGE or (address - file_offset) % PAGE:
                        errors.append(f'{name}: LOAD not 16 KB aligned ({alignment})')
                if kind == 0x6474E552:
                    # A RELRO suffix can safely end before a page boundary when
                    # the remainder is padding, rather than mutable LOAD data.
                    end = address + mem_size
                    protected_start = address // PAGE * PAGE
                    protected_end = (end + PAGE - 1) // PAGE * PAGE
                    for load in headers:
                        if load[0] != 1 or not load[1] & 2:
                            continue
                        start = max(protected_start, load[3])
                        stop = min(protected_end, load[3] + load[6])
                        if start < stop and (start < address or stop > end):
                            errors.append(f'{name}: RELRO page overlaps writable data')
            if not loads:
                errors.append(f'{name}: missing LOAD segments')
            if info.compress_type == zipfile.ZIP_STORED:
                raw.seek(info.header_offset + 26)
                name_length, extra_length = struct.unpack('<HH', raw.read(4))
                data_offset = info.header_offset + 30 + name_length + extra_length
                if data_offset % PAGE:
                    errors.append(f'{name}: ZIP data offset not 16 KB aligned')
            print(f'Checked {name}')
    if count == 0:
        errors.append(f'{path}: no 64-bit native libraries found')
    return errors


if __name__ == '__main__':
    paths = sorted({Path(p) for pattern in sys.argv[1:] for p in glob.glob(pattern, recursive=True)})
    if not paths:
        sys.exit('No APK files found')
    errors = [error for path in paths for error in check_apk(path)]
    if errors:
        sys.exit('\n'.join(errors))
    print(f'PASS: 16 KB ELF/RELRO/ZIP alignment in {len(paths)} APK(s)')
