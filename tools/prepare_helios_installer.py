"""Reuse the existing verified PackageInstaller bridge for the separate Helios package."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
source=(ROOT.parent/'plugins/installbridge/InstallBridge.java').read_text(encoding='utf-8')
source=source.replace('InstallBridge','HeliosInstall').replace('        scheduleRelaunch(context);\n','')
source=source.replace('        commit(context, apk);','''        android.content.pm.PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (info == null || !"pl.mateusz.helios".equals(info.packageName)) throw new SecurityException("Unexpected APK package");
        if (!context.getPackageManager().canRequestPackageInstalls()) return "INSTALL_SOURCE_PERMISSION_REQUIRED";
        commit(context, apk);''')
target=ROOT/'.local/helios-installer'
target.mkdir(exist_ok=True)
(target/'HeliosInstall.java').write_text(source,encoding='utf-8')
print('Helios-only installer prepared; no relaunch alarm')
