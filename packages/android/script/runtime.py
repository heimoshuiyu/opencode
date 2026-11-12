"""Package Bionic executables in the APK's read-only nativeLibraryDir, not writable app data."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
cache = root / 'build/runtime-cache'
cache.mkdir(parents=True, exist_ok=True)
lock = [line.split() for line in (root / 'script/runtime.lock').read_text().splitlines() if line and not line.startswith('#')]
base = os.getenv('TERMUX_APT_MIRROR', 'https://packages.termux.dev/apt/termux-main').rstrip('/')

for column, (arch, abi) in enumerate([('aarch64', 'arm64-v8a'), ('x86_64', 'x86_64')], 1):
    with tempfile.TemporaryDirectory(dir=cache) as temp:
        prefix = Path(temp) / 'data/data/com.termux/files/usr'
        for row in lock:
            url = row[0].replace('ARCH', arch)
            deb = cache / Path(url).name
            if not deb.exists() or hashlib.sha256(deb.read_bytes()).hexdigest() != row[column]:
                subprocess.run(['curl', '-fLsS', '--retry', '3', f'{base}/{url}', '-o', deb], check=True)
            if hashlib.sha256(deb.read_bytes()).hexdigest() != row[column]:
                sys.exit(f'Checksum mismatch: {deb.name}')
            subprocess.run(['dpkg-deb', '-x', deb, temp], check=True)

        dest = Path(sys.argv[1]) / abi
        dest.mkdir(parents=True, exist_ok=True)
        pending = {prefix / 'bin/node': 'liboc_node.so', prefix / 'bin/rg': 'liboc_rg.so'}
        done = set()
        while pending:
            source, name = pending.popitem()
            if name in done:
                continue
            done.add(name)
            target = dest / name
            shutil.copyfile(source, target)
            target.chmod(0o755)
            args = ['patchelf', '--page-size', '16384', '--set-rpath', '$ORIGIN']
            if source.parent.name == 'lib':
                args.extend(['--set-soname', name])
            for lib in subprocess.check_output(['patchelf', '--print-needed', source], text=True).split():
                child = prefix / 'lib' / lib
                if not child.exists():
                    if lib not in {'libc.so', 'libdl.so', 'libm.so', 'liblog.so'}:
                        sys.exit(f'Unpackaged dependency of {name}: {lib}')
                    continue
                # The installer only extracts lib*.so; avoid Android's system ICU/OpenSSL namespaces.
                replacement = 'liboc_' + lib.removeprefix('lib').split('.so')[0] + '.so'
                args.extend(['--replace-needed', lib, replacement])
                pending[child] = replacement
            subprocess.run([*args, target], check=True)
        if column == 1:
            for source in (prefix / 'share/doc').glob('*/*'):
                if not source.name.lower().startswith(('copyright', 'license', 'licence', 'copying')):
                    continue
                target = Path(sys.argv[2]) / 'licenses' / source.parent.name / source.name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
