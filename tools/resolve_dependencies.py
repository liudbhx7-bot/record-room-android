#!/usr/bin/env python3
"""Resolve the pinned Android runtime graph with Maven; leave a verifiable local artifact inventory."""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--maven',default=shutil.which('mvn') or shutil.which('mvn.cmd'))
    parser.add_argument('--repository',type=Path,default=ROOT/'tools/dependencies/repository')
    parser.add_argument('--out',type=Path,default=ROOT/'tools/dependencies/lib')
    parser.add_argument('--apple-sdk',type=Path,help='Official unpacked Android-MusicKit-SDK-1.1.4 directory; cached verified AARs may be reused')
    args=parser.parse_args()
    if not args.maven: raise SystemExit('Maven 3.9+ is required; pass --maven PATH.')
    output=args.out.resolve();output.mkdir(parents=True,exist_ok=True)
    apple=json.loads((ROOT/'tools/dependencies/apple-sdk.json').read_text(encoding='utf-8'))
    apple_sources=[]
    for item in apple['artifacts']:
        source=(args.apple_sdk/item['filename']) if args.apple_sdk else output/'vendor/apple'/item['filename']
        if not source.is_file():raise SystemExit('Official Apple SDK missing: '+str(source)+'; pass --apple-sdk PATH_TO_UNPACKED_SDK.')
        if hashlib.sha256(source.read_bytes()).hexdigest()!=item['sha256']:raise SystemExit('Apple SDK checksum mismatch: '+item['filename'])
        apple_sources.append(source.resolve())
    maven_version=subprocess.run([args.maven,'-version'],check=True,capture_output=True,text=True,timeout=30).stdout.splitlines()[0]
    with tempfile.TemporaryDirectory(prefix='runtime-resolve-',dir=output.parent) as temporary:
        staging=Path(temporary)
        command=[args.maven,'-B','-ntp','-s',str(ROOT/'tools/dependencies/settings.xml'),'-f',str(ROOT/'tools/dependencies/pom.xml'),
                 '-Dmaven.repo.local='+str(args.repository.resolve()),'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
                 '-DincludeScope=runtime','-Dmdep.useRepositoryLayout=true','-DoutputDirectory='+str(staging)]
        subprocess.run(command,check=True,timeout=600)
        files=[]
        for source in sorted(p for p in staging.rglob('*') if p.suffix in ('.aar','.jar')):
            target=output/source.relative_to(staging);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target);files.append(target)
    for source in apple_sources:
        target=output/'vendor/apple'/source.name;target.parent.mkdir(parents=True,exist_ok=True)
        if source!=target.resolve():shutil.copy2(source,target)
        files.append(target)
    ns={'m':'http://maven.apache.org/POM/4.0.0'}
    roots=[]
    for dependency in ET.parse(ROOT/'tools/dependencies/pom.xml').getroot().findall('m:dependencies/m:dependency',ns):
        roots.append(':'.join(dependency.findtext('m:'+key,default='jar' if key=='type' else '',namespaces=ns) for key in ('groupId','artifactId','version','type')))
    inventory={'maven_version':maven_version,'resolver_plugin':'org.apache.maven.plugins:maven-dependency-plugin:3.8.1','root_dependencies':roots,'apple_sdk':apple,
               'artifacts':[{'path':p.relative_to(output).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size} for p in files]}
    (output/'dependencies.json').write_text(json.dumps(inventory,indent=2)+'\n',encoding='utf-8')
    print('Resolved',len(files),'artifacts to',output)

if __name__=='__main__':main()
