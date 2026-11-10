#!/usr/bin/env python3
"""Render actual Android Java sources through Robolectric Native Graphics."""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, subprocess, tempfile, zipfile

base = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument('--project', type=Path, required=True)
parser.add_argument('--deps', type=Path, default=base/'lib')
parser.add_argument('--out', type=Path, default=base/'outputs')
parser.add_argument('--tests', choices=['all', 'main', 'components'], default='all')
parser.add_argument('--test-class',action='append',help='Run selected fully qualified test classes')
args = parser.parse_args()
project, deps, output = args.project.resolve(), args.deps.resolve(), args.out.resolve()
output.mkdir(parents=True, exist_ok=True)
if not (project/'build-manual/base.apk').exists():
    raise SystemExit('Build the Android project first: build-manual/base.apk is required.')
if not (deps/'robolectric-4.17.jar').exists():
    raise SystemExit('Resolve dependencies with the Maven command in README.md first.')
java_home = os.environ.get('JAVA_HOME')
java_candidate = Path(java_home)/'bin'/('java.exe' if os.name=='nt' else 'java') if java_home else None
java = str(java_candidate) if java_candidate and java_candidate.exists() else shutil.which('java')
if java is None: raise SystemExit('JDK 17+ java is required.')

def java_args(path, arguments):
    # Java launchers support @argfiles; avoid Windows command-line length limits.
    path.write_text('\n'.join('"'+str(a).replace('\\','/').replace('"','\\"')+'"' for a in arguments), encoding='utf-8')
    return '@'+str(path)

with tempfile.TemporaryDirectory(prefix='vinyl-native-qa-') as directory:
    work = Path(directory); src = work/'src/com/recordroom/vinyl'; classes = work/'classes'; aar = work/'aar'
    for folder in [src, classes, aar]: folder.mkdir(parents=True, exist_ok=True)
    for p in (project/'app/src/main/java/com/recordroom/vinyl').glob('*.java'): shutil.copy2(p, src/p.name)
    shutil.copy2(project/'build-manual/gen/com/recordroom/vinyl/R.java', src/'R.java')
    generated=work/'generated';generated.mkdir()
    for p in (project/'build-manual/gen').rglob('*.java'):
        if p.relative_to(project/'build-manual/gen').as_posix()=='com/recordroom/vinyl/R.java':continue
        target=generated/p.relative_to(project/'build-manual/gen');target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
    for p in (base/'tests/com/recordroom/vinyl').glob('*.java'): shutil.copy2(p, src/p.name)
    for p in (base/'compat').glob('*.java'): shutil.copy2(p, src/p.name)
    for p in deps.glob('*.aar'):
        with zipfile.ZipFile(p) as z:
            if 'classes.jar' in z.namelist(): (aar/(p.stem+'.jar')).write_bytes(z.read('classes.jar'))
    runtime_file=project/'build-manual/runtime-dependencies.json'
    runtime=json.loads(runtime_file.read_text(encoding='utf-8')) if runtime_file.exists() else {'jars':[]}
    jars = [*deps.glob('*.jar'), *aar.glob('*.jar'), *map(Path,runtime['jars'])]
    cp = os.pathsep.join(str(p) for p in jars)
    sources = sorted(src.glob('*.java'))
    compiler_args = ['-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-source', '17', '-target', '17', '-classpath', cp, '-d', str(classes), *map(str, sources), *map(str,generated.rglob('*.java')),str(base/'launcher/NativeQaMain.java')]
    subprocess.run([java, java_args(work/'javac.args', compiler_args)], check=True)
    compat_config=base/'compat/robolectric.properties'
    if compat_config.is_file(): shutil.copy2(compat_config,classes/'robolectric.properties')
    props = classes/'com/android/tools/test_config.properties'; props.parent.mkdir(parents=True, exist_ok=True)
    props.write_text('\n'.join([
        'android_merged_manifest='+Path(runtime.get('manifest',project/'app/src/main/AndroidManifest.xml')).as_posix(),
        'android_merged_resources='+(project/'app/src/main/res').as_posix(),
        'android_merged_assets='+(project/'app/src/main/assets').as_posix(),
        'android_resource_apk='+(project/'build-manual/base.apk').as_posix(),
        'android_custom_package=com.recordroom.vinyl', ''
    ]))
    opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.io', 'java.base/java.net', 'java.base/java.security', 'java.base/java.text', 'java.base/jdk.internal.access', 'java.desktop/java.awt.font', 'jdk.compiler/com.sun.tools.javac.api']
    command = [java, '-Xmx2g', '-Djava.awt.headless=true', '-Drobolectric.usePreinstrumentedJars=false', '-Drobolectric.offline=true', '-Drobolectric.dependency.dir='+str(deps), '-Drobolectric.enabledSdks=34', '-Dvinyl.project.dir='+str(project), '-Dvinyl.output.dir='+str(output)]
    command += ['--add-opens='+s+'=ALL-UNNAMED' for s in opens]
    test_classes = ['com.recordroom.vinyl.'+p.stem for p in sorted((base/'tests/com/recordroom/vinyl').glob('*Test.java'))]
    if args.tests == 'main': test_classes = ['com.recordroom.vinyl.MainRenderTest']
    elif args.tests == 'components': test_classes = ['com.recordroom.vinyl.NativeRenderTest']
    if args.test_class:test_classes=args.test_class
    command += ['-classpath', str(classes)+os.pathsep+cp, 'NativeQaMain', *test_classes]
    result = subprocess.run([java, java_args(work/'tests.args', command[1:])], cwd=project)
    graphics_modes = {}
    for test_class in test_classes:
        code = (src/(test_class.rsplit('.',1)[1]+'.java')).read_text(encoding='utf-8')
        mode = re.search(r'@GraphicsMode\(GraphicsMode\.Mode\.(\w+)\)', code)
        graphics_modes[test_class] = mode.group(1) if mode else 'LEGACY (framework default)'
    report = {'framework':'Robolectric 4.17; native render tests and Android state/input tests', 'sdk':34, 'test_classes':test_classes, 'graphics_modes':graphics_modes, 'device_run':False, 'exit_code':result.returncode, 'source_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}, 'pngs':[p.name for p in output.glob('*.png')]}
    report['resource_apk_sha256']=hashlib.sha256((project/'build-manual/base.apk').read_bytes()).hexdigest()
    report['generated_library_sha256']={p.relative_to(generated).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in generated.rglob('*.java')}
    report['launcher_sha256']=hashlib.sha256((base/'launcher/NativeQaMain.java').read_bytes()).hexdigest()
    report['qa_compatibility_sha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((base/'compat').glob('*')) if p.is_file()}
    inventory=project/'tools/dependencies/lib/dependencies.json'
    if inventory.is_file():report['runtime_inventory_sha256']=hashlib.sha256(inventory.read_bytes()).hexdigest()
    (output/'render-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(result.returncode)
