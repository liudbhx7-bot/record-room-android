#!/usr/bin/env python3
"""Build the native Android app and its verified AAR/JAR libraries with official SDK tools.

Requires JDK 17+ and Android platform/build-tools 36. Does not download software.
Works with a standard Android Studio SDK or the extracted official SDK archives.
"""
from pathlib import Path
import argparse, hashlib, json, os, shutil, struct, subprocess, sys, zipfile
import xml.etree.ElementTree as ET
from android_dependencies import prepare, elf_load_alignments
from apple_auth_logging import sanitize_auth

ROOT = Path(__file__).resolve().parents[1]

def run(args):
    print('>', ' '.join(str(x) for x in args), flush=True)
    subprocess.run([str(x) for x in args], check=True)

def one(root, filename, contains=''):
    found = [p for p in root.rglob(filename) if not contains or contains in str(p)]
    if not found: raise SystemExit('SDK file missing: ' + filename)
    return sorted(found, key=lambda p: str(p))[-1]

def java_tool():
    home = os.environ.get('JAVA_HOME')
    candidate = Path(home)/'bin'/('java.exe' if os.name == 'nt' else 'java') if home else None
    return str(candidate) if candidate and candidate.exists() else shutil.which('java')

def argfile(path, arguments):
    path.write_text('\n'.join('"'+str(a).replace('\\','/').replace('"','\\"')+'"' for a in arguments), encoding='utf-8')
    return '@'+str(path)

def jar_resources(jars):
    result={}
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            for entry in archive.infolist():
                name=entry.filename.replace('\\','/');upper=name.upper()
                if entry.is_dir() or name.endswith('.class') or name.startswith(('META-INF/proguard/','META-INF/com.android.tools/proguard/')) or upper in ('META-INF/MANIFEST.MF','META-INF/INDEX.LIST') or upper.endswith(('.SF','.RSA','.DSA','.EC')):continue
                if name.startswith('/') or '..' in name.split('/'):raise SystemExit('Invalid JAR resource path: '+name)
                payload=archive.read(entry)
                if name in result and result[name]!=payload:
                    if name.startswith('META-INF/services/') or any(word in upper.rsplit('/',1)[-1] for word in ('LICENSE','NOTICE','COPYING')):result[name]+=b'\n'+payload
                    else:raise SystemExit('Conflicting runtime resource: '+name)
                else:result[name]=payload
    return result

def main():
    parser=argparse.ArgumentParser()
    default_sdk = Path(os.environ.get('LOCALAPPDATA', str(Path.home())))/'Android/Sdk' if os.name == 'nt' else Path.home()/'Library/Android/sdk'
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or str(default_sdk))
    parser.add_argument('--platform-jar', type=Path, help='Explicit Android platform android.jar (API 36 required for release builds)')
    parser.add_argument('--out', default=str(ROOT/'dist'))
    parser.add_argument('--deps',type=Path,default=ROOT/'tools/dependencies/lib',help='Verified runtime libraries from resolve_dependencies.py')
    args=parser.parse_args();sdk=Path(args.sdk).expanduser().resolve();out=Path(args.out).resolve();out.mkdir(parents=True,exist_ok=True)
    if not sdk.exists():raise SystemExit('Android SDK not found. Install API 36 and Build Tools 36 with Android Studio, then pass --sdk PATH.')
    android=args.platform_jar.resolve() if args.platform_jar else one(sdk/'platforms','android.jar','36')
    executable='.exe' if os.name=='nt' else ''
    aapt2=one(sdk/'build-tools','aapt2'+executable);zipalign=one(sdk/'build-tools','zipalign'+executable);d8=one(sdk/'build-tools','d8.jar');signer=one(sdk/'build-tools','apksigner.jar')
    java=java_tool()
    if not java:raise SystemExit('JDK 17+ java was not found in PATH.')
    key=ROOT/'tools/development.keystore'
    if not key.is_file():raise SystemExit('tools/development.keystore is missing. Follow docs/BUILDING.md to create your own local development key; the original Release signing key is not included.')
    build=(ROOT/'build-manual').resolve()
    if build.parent != ROOT.resolve():raise SystemExit('Unsafe build output path.')
    shutil.rmtree(build,ignore_errors=True)
    for d in ['gen','classes','dex']:(build/d).mkdir(parents=True,exist_ok=True)
    main=ROOT/'app/src/main'
    dependencies=prepare(args.deps.resolve(),build,main/'AndroidManifest.xml')
    codecs=ROOT/'work/audio-codecs/jni'
    for abi in ('arm64-v8a', 'armeabi-v7a'):
        library=codecs/abi/'librecord_formats.so'
        if not library.is_file():
            raise SystemExit('Build the local audio reader with tools/audio/build_codecs.py first. See docs/BUILDING.md.')
        bits,segments=elf_load_alignments(library.read_bytes())
        if min(segments)<16384:raise SystemExit('Local audio reader must be 16 KB aligned')
        dependencies['native_libraries'].append({'path':str(library.relative_to(codecs)),'bits':bits,'load_alignments':segments})
    dependencies['natives'].append(str(codecs))
    if dependencies['min_compile_sdk']>=36:
        with zipfile.ZipFile(android) as platform:
            if b'BAKLAVA' not in platform.read('android/os/Build$VERSION_CODES.class'):
                raise SystemExit('Runtime libraries require Android SDK36; pass its android.jar with --platform-jar.')
    manifest=ET.parse(main/'AndroidManifest.xml').getroot()
    version_code=manifest.attrib['{http://schemas.android.com/apk/res/android}versionCode']
    version_name=manifest.attrib['{http://schemas.android.com/apk/res/android}versionName']
    compiled=[]
    for index,resources in enumerate([*dependencies['resources'],main/'res']):
        target=build/('resources-'+str(index)+'.zip');run([aapt2,'compile','--dir',resources,'-o',target]);compiled.extend(['-R',target])
    extra=['--extra-packages',':'.join(dependencies['packages'])] if dependencies['packages'] else []
    asset_args=[]
    for folder in [*dependencies['assets'],main/'assets']:asset_args.extend(['-A',folder])
    run([aapt2,'link','-I',android,'--manifest',dependencies['manifest'],'--java',build/'gen','-o',build/'base.apk','--auto-add-overlay',*extra,*compiled,*asset_args,'-0','wav','--min-sdk-version','26','--target-sdk-version','36','--version-code',version_code,'--version-name',version_name])
    # Windows aapt2 can put backslashes in local asset ZIP headers even when
    # central-directory names use '/'. Robolectric reads local headers; rebuild
    # this intermediate APK with canonical ZIP names, just as the final APK is.
    normalized = build/'base-normalized.apk'
    with zipfile.ZipFile(build/'base.apk') as src, zipfile.ZipFile(normalized, 'w') as dest:
        for info in src.infolist():
            payload = src.read(info)
            info.filename = info.filename.replace('\\', '/')
            dest.writestr(info, payload)
    normalized.replace(build/'base.apk')
    sources=sorted((main/'java').rglob('*.java'))+sorted((build/'gen').rglob('*.java'))
    compiler_args=['-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','-source','17','-target','17','-classpath',os.pathsep.join([str(android),*dependencies['jars']]),'-d',build/'classes',*sources]
    run([java,argfile(build/'javac.args',compiler_args)])
    with zipfile.ZipFile(build/'classes.jar','w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted((build/'classes').rglob('*.class')):z.write(p,p.relative_to(build/'classes').as_posix())
    sanitize_auth(dependencies,build,android,java,d8)
    dex_args=['-cp',d8,'com.android.tools.r8.D8','--min-api','26','--lib',android,'--output',build/'dex',build/'classes.jar',*dependencies['jars']]
    run([java,argfile(build/'d8.args',dex_args)])
    with zipfile.ZipFile(build/'base.apk') as src,zipfile.ZipFile(build/'unsigned.apk','w') as dest:
        for info in src.infolist():dest.writestr(info,src.read(info.filename))
        for name,payload in jar_resources(dependencies['jars']).items():
            if name in src.namelist():
                if src.read(name)!=payload:raise SystemExit('Dependency overlaps Android resource: '+name)
            else:dest.writestr(name,payload,compress_type=zipfile.ZIP_DEFLATED)
        for p in sorted((build/'dex').glob('*.dex')):dest.write(p,p.name,compress_type=zipfile.ZIP_STORED)
        native_names={}
        for folder in dependencies['natives']:
            for p in sorted(Path(folder).rglob('*.so')):
                name='lib/'+p.relative_to(folder).as_posix();payload=p.read_bytes()
                if name in native_names:
                    if native_names[name]!=payload:raise SystemExit('Conflicting native runtime library: '+name)
                    continue
                native_names[name]=payload;dest.writestr(name,payload,compress_type=zipfile.ZIP_STORED)
    run([zipalign,'-f','-P','16','4',build/'unsigned.apk',build/'aligned.apk'])
    apk=out/('vinyl-baseline-v'+version_name+'.apk')
    run([java,'-jar',signer,'sign','--ks',key,'--ks-key-alias','baseline','--ks-pass','pass:android','--key-pass','pass:android','--v4-signing-enabled','false','--out',apk,build/'aligned.apk'])
    run([java,'-jar',signer,'verify','--verbose',apk])
    run([zipalign,'-c','-P','16','4',apk])
    native_report=[]
    with zipfile.ZipFile(apk) as archive,apk.open('rb') as stream:
        for info in archive.infolist():
            if not info.filename.startswith('lib/') or not info.filename.endswith('.so'):continue
            stream.seek(info.header_offset+26);name_length,extra_length=struct.unpack('<HH',stream.read(4))
            offset=info.header_offset+30+name_length+extra_length
            if info.compress_type!=zipfile.ZIP_STORED or offset%16384:raise SystemExit('Native APK entry is not stored/16-KB aligned: '+info.filename)
            native_report.append({'path':info.filename,'zip_data_offset':offset,'zip_16kb_aligned':True})
    (build/'native-alignment.json').write_text(json.dumps({'elf':dependencies['native_libraries'],'apk_entries':native_report,'zipalign_16kb_verified':True},indent=2)+'\n')
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    (out/'SHA256SUMS.txt').write_text(digest+'  '+apk.name+'\n')
    print('Built:',apk,'bytes:',apk.stat().st_size,'sha256:',digest)

if __name__=='__main__':main()
