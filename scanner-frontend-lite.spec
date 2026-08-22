# -*- mode: python ; coding: utf-8 -*-


a = Analysis(
    ['/home/gibi/Desktop/navigation/frontend/dev_server.py'],
    pathex=[],
    binaries=[],
    datas=[('/home/gibi/Desktop/navigation/frontend/index.html', '.'), ('/home/gibi/Desktop/navigation/frontend/app.js', '.'), ('/home/gibi/Desktop/navigation/frontend/styles.css', '.')],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='scanner-frontend-lite',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='scanner-frontend-lite',
)
