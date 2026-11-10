"""Prepare verified Maven AAR/JAR artifacts for the small SDK-tool build pipeline."""
from pathlib import Path
import copy, hashlib, json, struct, zipfile
import xml.etree.ElementTree as ET

ANDROID='http://schemas.android.com/apk/res/android'
TOOLS='http://schemas.android.com/tools'
ET.register_namespace('android',ANDROID)

def elf_load_alignments(payload):
    """Read real ELF program headers; ZIP alignment alone does not make native code 16-KB compatible."""
    if payload[:4]!=b'\x7fELF' or payload[4] not in (1,2) or payload[5] not in (1,2):
        raise ValueError('Not a supported ELF library')
    bits=64 if payload[4]==2 else 32;endian='<' if payload[5]==1 else '>'
    offset=struct.unpack_from(endian+('Q' if bits==64 else 'I'),payload,32 if bits==64 else 28)[0]
    entry_size,count=struct.unpack_from(endian+'HH',payload,54 if bits==64 else 42)
    alignments=[]
    for index in range(count):
        header=offset+index*entry_size
        if struct.unpack_from(endian+'I',payload,header)[0]==1:
            alignments.append(struct.unpack_from(endian+('Q' if bits==64 else 'I'),payload,header+(48 if bits==64 else 28))[0])
    if not alignments:raise ValueError('ELF library has no LOAD segments')
    return bits,alignments

def node_key(node):
    name=node.get('{'+ANDROID+'}name')
    if name is not None:return node.tag,name
    if node.tag in ('application','queries','supports-screens'):return node.tag,''
    # Separate intent filters/queries with different actions or data must both survive.
    return node.tag,ET.tostring(node,encoding='unicode')

def merge_node(parent,incoming):
    existing=next((child for child in parent if node_key(child)==node_key(incoming)),None)
    if existing is None:parent.append(incoming);return
    for name,value in incoming.attrib.items():
        if name not in existing.attrib:existing.set(name,value)
    for child in incoming:merge_node(existing,copy.deepcopy(child))

def generate_binding_mapper(jars,build):
    """The aggregate normally emitted by AGP delegates to the actual AAR-generated binders."""
    mappers=set();aggregate='androidx/databinding/DataBinderMapperImpl.class'
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            names=archive.namelist()
            if aggregate in names:return
            mappers.update(name[:-6].replace('/','.') for name in names if name.endswith('/DataBinderMapperImpl.class'))
    if not mappers:return
    target=build/'gen/androidx/databinding/DataBinderMapperImpl.java';target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text('package androidx.databinding;\n// Generated from verified AAR mapper classes; no bindings are replaced.\n'
        +'public final class DataBinderMapperImpl extends MergedDataBinderMapper {\n  public DataBinderMapperImpl() {\n'
        +''.join('    addMapper(new '+name+'());\n' for name in sorted(mappers))+'  }\n}\n',encoding='utf-8')

def prepare(dependencies, build, app_manifest):
    inventory=dependencies/'dependencies.json'
    if not inventory.is_file():raise SystemExit('Resolve runtime libraries first with tools/resolve_dependencies.py; missing '+str(inventory))
    entries=json.loads(inventory.read_text(encoding='utf-8'))['artifacts']
    root=ET.parse(app_manifest).getroot();app=root.find('application');package=root.get('package')
    output=build/'dependencies';output.mkdir(parents=True,exist_ok=True)
    jars=[];resources=[];assets=[];natives=[];packages=[];required_sdk=1
    def clean(node,library_package):
        for name,value in list(node.attrib.items()):
            if name.startswith('{'+TOOLS+'}'):del node.attrib[name];continue
            node.attrib[name]=value.replace('${applicationId}',package)
        if node.tag in ('activity','activity-alias','service','receiver','provider'):
            for attribute in ('name','targetActivity'):
                name=node.get('{'+ANDROID+'}'+attribute)
                if name and name.startswith('.'):node.set('{'+ANDROID+'}'+attribute,library_package+name)
                elif name and '.' not in name:node.set('{'+ANDROID+'}'+attribute,library_package+'.'+name)
        for child in node:clean(child,library_package)
        return node
    for item in entries:
        source=(dependencies/item['path']).resolve()
        if not source.is_relative_to(dependencies.resolve()) or not source.is_file():raise SystemExit('Missing dependency '+item['path'])
        if hashlib.sha256(source.read_bytes()).hexdigest()!=item['sha256']:raise SystemExit('Dependency checksum mismatch: '+item['path'])
        if source.suffix=='.jar':jars.append(source);continue
        directory=output/('aar-'+str(len(packages))+'-'+source.stem);directory.mkdir()
        with zipfile.ZipFile(source) as archive:
            for info in archive.infolist():
                path=(directory/info.filename).resolve()
                if not path.is_relative_to(directory.resolve()):raise SystemExit('Invalid AAR path: '+info.filename)
            archive.extractall(directory)
        # lint.jar and other build-time tooling in an AAR must never enter app dex.
        if (directory/'classes.jar').is_file():jars.append(directory/'classes.jar')
        if (directory/'libs').is_dir():jars.extend(sorted((directory/'libs').rglob('*.jar')))
        if (directory/'res').is_dir():resources.append(directory/'res')
        if (directory/'assets').is_dir():assets.append(directory/'assets')
        if (directory/'jni').is_dir():natives.append(directory/'jni')
        metadata=directory/'META-INF/com/android/build/gradle/aar-metadata.properties'
        if metadata.is_file():
            for line in metadata.read_text().splitlines():
                if line.startswith('minCompileSdk='):required_sdk=max(required_sdk,int(line.split('=',1)[1]))
                if line=='coreLibraryDesugaringEnabled=true':raise SystemExit('Dependency requires unsupported core library desugaring: '+item['path'])
        manifest=directory/'AndroidManifest.xml'
        if manifest.is_file():
            library=ET.parse(manifest).getroot();library_package=library.get('package','')
            if library_package and library_package not in packages:packages.append(library_package)
            for child in library:
                if child.tag=='uses-sdk':
                    minimum=child.get('{'+ANDROID+'}minSdkVersion','1')
                    if minimum.isdigit() and int(minimum)>26:raise SystemExit('Dependency exceeds supported minSdk26: '+item['path'])
                    continue
                if child.tag=='application':
                    for name,value in child.attrib.items():
                        if not name.startswith('{'+TOOLS+'}') and name not in app.attrib:app.set(name,value.replace('${applicationId}',package))
                    for node in child:
                        node=clean(copy.deepcopy(node),library_package)
                        merge_node(app,node)
                else:
                    node=clean(copy.deepcopy(child),library_package)
                    merge_node(root,node)
    generate_binding_mapper(jars,build)
    native_report=[]
    for folder in natives:
        for native in sorted(folder.rglob('*.so')):
            payload=native.read_bytes();bits,alignment=elf_load_alignments(payload)
            record={'path':native.relative_to(folder).as_posix(),'sha256':hashlib.sha256(payload).hexdigest(),
                    'elf_bits':bits,'load_alignments':alignment,'supports_16kb_elf':all(value>=16384 for value in alignment)}
            native_report.append(record)
            if bits==64 and not record['supports_16kb_elf']:raise SystemExit('64-bit native dependency is not 16-KB aligned: '+str(native))
    merged=build/'AndroidManifest.xml';ET.ElementTree(root).write(merged,encoding='utf-8',xml_declaration=True)
    result={'jars':[str(p) for p in jars],'resources':[str(p) for p in resources],'assets':[str(p) for p in assets],
            'natives':[str(p) for p in natives],'native_libraries':native_report,'packages':packages,'manifest':str(merged),'min_compile_sdk':required_sdk}
    (build/'runtime-dependencies.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    return result
