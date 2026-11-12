import SwiftUI
import UniformTypeIdentifiers
import CryptoKit
import AVFoundation

private struct Verse:Identifiable {
    let id:Int
    let start:Double?
    let words:String
}

private enum VerseReader {
    private static let limit = 256 * 1024
    private static let tag = try! NSRegularExpression(pattern:#"^\[(\d{1,3}):([0-5]\d)(?:[.:](\d{1,3}))?\]"#)
    private static let shift = try! NSRegularExpression(pattern:#"(?im)^\s*\[offset:([+-]?\d{1,8})\]\s*$"#)

    static func parse(_ text:String)->[Verse] {
        let input = text as NSString
        let range=NSRange(location:0,length:input.length)
        var offset=0.0
        if let m=shift.firstMatch(in:text,range:range) {offset = (Double(input.substring(with:m.range(at:1))) ?? 0)/1000}
        var result:[Verse] = []
        var characters=0
        for row in text.split(whereSeparator: \.isNewline) {
            let raw=String(row).trimmingCharacters(in:.whitespacesAndNewlines)
            let ns=raw as NSString
            var index=0
            var stamps:[Double]=[]
            while index<ns.length, stamps.count<32,
                  let m=tag.firstMatch(in:raw,options:[.anchored],range:NSRange(location:index,length:ns.length-index)) {
                let minutes=Double(ns.substring(with:m.range(at:1))) ?? 0
                let second=Double(ns.substring(with:m.range(at:2))) ?? 0
                let subrange=m.range(at:3)
                let fraction = subrange.location==NSNotFound ? 0 : (Double("0."+ns.substring(with:subrange)) ?? 0)
                stamps.append(minutes*60+second+fraction-offset);index=NSMaxRange(m.range)
            }
            let words=ns.substring(from:index).trimmingCharacters(in:.whitespaces)
            guard !words.isEmpty else {continue}
            if stamps.isEmpty && words.hasPrefix("[") && words.hasSuffix("]") {continue}
            let times:[Double?] = stamps.isEmpty ? [nil] : stamps.map{Optional($0)}
            for time in times {
                characters += words.utf8.count
                if result.count >= 2048 || characters > limit {return result}
                result.append(Verse(id:result.count,start:time,words:words))
            }
        }
        if result.allSatisfy({$0.start != nil}) {result.sort{($0.start!, $0.id)<($1.start!, $1.id)}}
        return result
    }

    static func file(for id:String) throws -> URL {
        let dir=try FileManager.default.url(for:.applicationSupportDirectory,in:.userDomainMask,appropriateFor:nil,create:true)
            .appendingPathComponent("RecordRoom/Lyrics",isDirectory:true)
        try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true)
        let name=SHA256.hash(data:Data(id.utf8)).map{String(format:"%02x",$0)}.joined()
        return dir.appendingPathComponent(name+".txt")
    }

    static func load(track:RoomTrack) async -> [Verse] {
        if let url=try? file(for:track.id),let data=try? Data(contentsOf:url),
            let text=String(data:data,encoding:.utf8) {return parse(text)}
        guard track.audioURL.isFileURL else {return []}
        do {
            if let embedded=try await AVURLAsset(url:track.audioURL).load(.lyrics),embedded.utf8.count<=limit {
                return parse(embedded)
            }
        } catch {}
        return []
    }

    static func save(source:URL,id:String) throws -> [Verse] {
        let access=source.startAccessingSecurityScopedResource()
        defer{if access {source.stopAccessingSecurityScopedResource()}}
        let handle=try FileHandle(forReadingFrom:source);defer{try? handle.close()}
        guard let data=try handle.read(upToCount:limit+1),data.count<=limit else {throw ReadIssue.large}
        let text:String?
        if data.starts(with:[0xff,0xfe]) || data.starts(with:[0xfe,0xff]) {text=String(data:data,encoding:.utf16)}
        else {text=String(data:data,encoding:.utf8)}
        guard let text,!text.contains("\u{0000}") else {throw ReadIssue.encoding}
        let raw=text.trimmingCharacters(in:CharacterSet(charactersIn:"\u{feff}")), lines=parse(raw)
        guard !lines.isEmpty else {throw ReadIssue.encoding}
        try Data(raw.utf8).write(to:file(for:id),options:.atomic)
        return lines
    }
    enum ReadIssue:LocalizedError {
        case large,encoding
        var errorDescription:String? {self == .large ? "请选择 256 KB 以内的歌词文件。":"请选择含歌词的 UTF-8 或 UTF-16 文本文件。"}
    }
}

struct RoomLyrics:View {
    @EnvironmentObject private var player:LibraryPlayer
    @State private var lines:[Verse]=[]
    @State private var importing=false
    @State private var target:String?
    @State private var loading=true
    var body:some View {
        let current=lines.last(where:{($0.start ?? .infinity)<=player.position})?.id
        ScrollViewReader { reader in
            RoomColumn {
                Text(player.current?.title ?? "歌词").font(.system(size:32,weight:.semibold,design:.serif))
                if loading {ProgressView()}
                else if lines.isEmpty {Text("暂无歌词，可为这首歌导入 LRC 或 TXT。").foregroundStyle(.secondary).padding(.vertical)}
                LazyVStack(alignment:.leading,spacing:14) {
                    ForEach(lines) { line in
                        Text(line.words).font(.system(.title3,design:.serif,weight:current == line.id ? .semibold:.regular))
                            .foregroundStyle(.white.opacity(line.start == nil || line.id==current ? 1:0.64)).id(line.id)
                    }
                }
                if player.current != nil {
                    Button {target=player.current?.id;importing=true} label:{Label("导入歌词",systemImage:"doc.badge.plus")}.buttonStyle(.bordered)
                }
            }.onChange(of:current){_,value in if let value {withAnimation(.easeOut(duration:0.2)){reader.scrollTo(value,anchor:.center)}}}
        }
        .task(id:player.current?.id) {
            lines=[];loading=true
            guard let track=player.current else {loading=false;return}
            let found=await VerseReader.load(track:track)
            guard !Task.isCancelled else{return}
            lines=found;loading=false
        }
        .fileImporter(isPresented:$importing,allowedContentTypes:[.plainText, UTType(filenameExtension:"lrc") ?? .text]) {result in
            guard let id=target else{return}
            do {
                let source=try result.get()
                Task {
                    do {
                        let found=try await Task.detached {try VerseReader.save(source:source,id:id)}.value
                        if player.current?.id==id {lines=found}
                    } catch {player.errorMessage=error.localizedDescription}
                }
            } catch {if (error as NSError).code != NSUserCancelledError {player.errorMessage=error.localizedDescription}}
        }
    }
}
