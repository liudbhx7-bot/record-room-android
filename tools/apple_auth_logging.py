"""Remove debug logging from only the verified Apple authorization SDK using official R8.

Auth 1.1.5 logs the music-user-token from three callback paths. Preserve all SDK
classes and members, then prove the log references are gone before packaging.
"""
from pathlib import Path
import hashlib, json, struct, subprocess, zipfile

def class_contract(data):
    if data[:4]!=b'\xca\xfe\xba\xbe':raise ValueError('Invalid JVM class')
    position=8
    def u2():
        nonlocal position
        value=struct.unpack_from('>H',data,position)[0];position+=2;return value
    count=u2();pool=[None]*count;index=1
    while index<count:
        tag=data[position];position+=1
        if tag==1:
            size=u2();pool[index]=(tag,data[position:position+size].decode('utf-8',errors='replace'));position+=size
        elif tag in (7,8,16,19,20):pool[index]=(tag,u2())
        elif tag in (9,10,11,12,17,18):pool[index]=(tag,u2(),u2())
        elif tag in (3,4):position+=4
        elif tag in (5,6):position+=8;index+=1
        elif tag==15:position+=3
        else:raise ValueError('Unsupported JVM constant tag '+str(tag))
        index+=1
    access=u2();name=pool[pool[u2()][1]][1];u2()
    interfaces=u2();position+=interfaces*2
    def members():
        nonlocal position
        result={}
        for _ in range(u2()):
            flags=u2();member=pool[u2()][1];descriptor=pool[u2()][1]
            result[(member,descriptor)]=flags
            for _ in range(u2()):
                u2();size=struct.unpack_from('>I',data,position)[0];position+=4+size
        return result
    fields=members();methods=members();logs=[]
    for item in pool:
        if not item or item[0] not in (10,11):continue
        owner=pool[pool[item[1]][1]][1];signature=pool[item[2]]
        method=pool[signature[1]][1];descriptor=pool[signature[2]][1]
        if owner=='android/util/Log' and method in ('d','v','i'):logs.append(method+descriptor)
    return {'name':name,'access':access,'fields':fields,'methods':methods,'debug_logs':logs}

def verify_auth_transform(original,transformed):
    with zipfile.ZipFile(original) as before,zipfile.ZipFile(transformed) as after:
        old={name for name in before.namelist() if name.endswith('.class')}
        new={name for name in after.namelist() if name.endswith('.class')}
        if old!=new:raise SystemExit('Apple auth R8 changed the SDK class inventory')
        eliminated=[]
        for name in sorted(old):
            a=class_contract(before.read(name));b=class_contract(after.read(name))
            if a['access']!=b['access'] or a['fields']!=b['fields'] or a['methods']!=b['methods']:
                raise SystemExit('Apple auth R8 changed an SDK member/access contract: '+name)
            if b['debug_logs']:raise SystemExit('Apple auth debug log reference survived: '+name)
            if a['debug_logs']:eliminated.append({'class':name,'removed_log_methods':a['debug_logs']})
        if not eliminated:raise SystemExit('Unexpected Apple auth binary: expected debug log sites were not found')
        return {'classes_preserved':len(old),'member_and_access_contracts_preserved':True,'debug_log_references_after':0,'eliminated':eliminated}

def sanitize_auth(dependencies,build,android,java,r8):
    matches=[Path(path) for path in dependencies['jars'] if 'MusicKitAuth-release-1.1.5' in path]
    if len(matches)!=1:raise SystemExit('Expected exactly one verified Apple Auth 1.1.5 runtime')
    source=matches[0];target=build/'apple-auth-no-debug-logging.jar';rules=build/'apple-auth-no-debug-logging.pro'
    rules.write_text('''# Only the Apple Auth AAR is program input. All other libraries are unchanged.
-keep,allowoptimization class ** { *; }
-keepattributes *
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
}
# JDK string-concat bootstrap is preserved in classfiles and desugared by the later D8 pass.
-dontwarn java.lang.invoke.StringConcatFactory
''',encoding='utf-8')
    arguments=['-cp',str(r8),'com.android.tools.r8.R8','--classfile','--no-tree-shaking','--no-minification','--no-desugaring',
        '--output',str(target),'--pg-conf',str(rules),'--lib',str(android),'--lib',str(build/'classes.jar')]
    for path in dependencies['jars']:
        if Path(path)!=source:arguments+=['--lib',str(path)]
    arguments.append(str(source))
    args=build/'apple-auth-r8.args';args.write_text('\n'.join('"'+value.replace('\\','/').replace('"','\\"')+'"' for value in arguments),encoding='utf-8')
    subprocess.run([str(java),'@'+str(args)],check=True)
    report=verify_auth_transform(source,target)
    report.update({'scope':'Official Apple MusicKitAuth 1.1.5 classes.jar only','input_path':str(source),'input_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
        'output_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'rules_sha256':hashlib.sha256(rules.read_bytes()).hexdigest(),
        'r8_sha256':hashlib.sha256(Path(r8).read_bytes()).hexdigest(),'tree_shaking':False,'minification':False})
    dependencies['jars']=[str(target) if Path(path)==source else path for path in dependencies['jars']]
    dependencies['apple_auth_logging']=report
    (build/'runtime-dependencies.json').write_text(json.dumps(dependencies,indent=2)+'\n',encoding='utf-8')
    (build/'apple-auth-logging.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print('Apple auth debug logging removed; classes/members preserved:',report['classes_preserved'],flush=True)
