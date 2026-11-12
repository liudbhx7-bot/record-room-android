import SwiftUI
import UniformTypeIdentifiers

@main
struct RecordRoomApp: App {
    @StateObject private var player = LibraryPlayer()
    var body: some Scene {
        WindowGroup("唱片机") {
            RoomHome().environmentObject(player)
            #if os(macOS)
                .frame(minWidth: 520, minHeight: 530)
            #endif
        }
        #if os(macOS)
        .defaultSize(width: 1020,height: 690)
        .commands {
            CommandMenu("播放") {
                Button(player.isPlaying ? "暂停" : "播放") { player.toggle() }.keyboardShortcut(" ",modifiers:[])
                Button("上一首") { player.previous() }.keyboardShortcut(.leftArrow,modifiers:.command)
                Button("下一首") { player.next() }.keyboardShortcut(.rightArrow,modifiers:.command)
            }
        }
        #endif
    }
}

enum RoomPage: Hashable {
    case library, sources, settings, search, queue, lyrics, apple, album(String), artist(String)
}

struct RoomHome: View {
    @EnvironmentObject private var player: LibraryPlayer
    @AppStorage("rr.coverColors") private var colors = true
    @AppStorage("rr.motion") private var motion = true
    @State private var path: [RoomPage] = []
    @State private var tools = false
    @State private var importing = false
    @State private var activePalette = RoomPalette.initial
    private var palette: RoomPalette { colors ? activePalette : .initial }
    private var cover: URL? { player.current?.artworkURL }
    var body: some View {
        NavigationStack(path:$path) {
            GeometryReader { g in
                let wide = g.size.width >= 680 && g.size.width > g.size.height*1.13
                ZStack {
                    palette.stage.ignoresSafeArea()
                    if wide {
                        HStack(spacing:0) {
                            deck.frame(width:g.size.width*0.53).padding(.vertical,16)
                            controls.frame(maxWidth:560).padding(.horizontal,max(24,g.size.width*0.043))
                        }
                    } else if g.size.height < 420 {
                        ScrollView { VStack(spacing:10) { deck.frame(height:300); controls.padding(20) } }
                    } else {
                        VStack(spacing:0) {
                            deck.padding(.top,6)
                            controls.padding(.horizontal, max(22,min(g.size.width*0.073,52)))
                                .padding(.top,9).padding(.bottom,max(16,min(g.size.height*0.055,48)))
                        }
                    }
                }
            }
            .navigationDestination(for:RoomPage.self) { page in
                #if os(iOS)
                pageView(page)
                    .navigationTitle(page == .apple ? "Apple Music" : "")
                    .navigationBarTitleDisplayMode(.inline)
                #else
                pageView(page).navigationTitle(pageTitle(page))
                #endif
            }
        }
        .preferredColorScheme(.dark)
        .tint(palette.light)
        .task(id:cover) {
            if let cover, let cg = await RoomImages.cover(cover) {
                let sample = RoomPalette.extract(cg)
                if !Task.isCancelled { activePalette = sample }
            } else if !Task.isCancelled { activePalette = .initial }
        }
        .fileImporter(isPresented:$importing,allowedContentTypes:importTypes,allowsMultipleSelection:true) { result in
            do { let urls=try result.get();Task { await player.importFiles(urls) } }
            catch { if (error as NSError).code != NSUserCancelledError { player.errorMessage=error.localizedDescription } }
        }
        .alert("唱片机",isPresented:Binding(get:{player.errorMessage != nil},set:{if !$0{player.errorMessage=nil}})) {
            Button("好") { player.errorMessage=nil }
        } message: { Text(player.errorMessage ?? "") }
        .sheet(isPresented:$tools) {
            PlayerTools(palette:palette) { route in
                tools=false
                path.append(route)
            }
            #if os(iOS)
            .presentationDetents([.height(288)]).presentationDragIndicator(.visible)
            #else
            .frame(width:445,height:278)
            #endif
        }
    }

    private var importTypes: [UTType] { [.audio] + ["flac","m4a","aif","aiff","caf","wav","mp3","alac"].compactMap{UTType(filenameExtension:$0)} }

    private var deck: some View {
        RecordDeck(cover:cover,palette:palette,playing:player.isPlaying,
                   fraction:player.duration>0 ? player.position/player.duration : 0,motion:motion,
                   openAlbum:openCurrentAlbum,toggle:player.toggle,seek:{player.seek(to:$0*player.duration)})
    }

    private func openCurrentAlbum() {
        if player.playSourceTitle != nil { path.append(.apple); return }
        if let t = player.current, let album = player.albums.first(where:{$0.tracks.contains(where:{$0.id==t.id})}) {path.append(.album(album.id))}
        else {path.append(.library)}
    }

    private var controls: some View {
        VStack(alignment:.leading,spacing:0) {
            HStack(alignment:.firstTextBaseline) {
                Button(action:openCurrentAlbum) {
                    Text(player.current?.title ?? "选择唱片")
                        .font(.system(size:33,weight:.semibold,design:.serif))
                        .foregroundStyle(.white).lineLimit(2).minimumScaleFactor(0.76)
                        .multilineTextAlignment(.leading)
                }.buttonStyle(.plain)
                Spacer(minLength:4)
                Button { tools=true } label: {
                    Image(systemName:"ellipsis").font(.system(size:18,weight:.bold)).frame(width:44,height:44)
                        .contentShape(Rectangle())
                }.buttonStyle(.plain).foregroundStyle(.white.opacity(0.72))
                    .accessibilityLabel("打开播放工具").accessibilityIdentifier("player.tools")
            }
            Button { if let artist=player.current?.artist {path.append(.artist(artist))} }
            label: { Text(player.current?.artist ?? "从资料库导入音乐").font(.system(.body,design:.serif)).lineLimit(1).foregroundStyle(.white.opacity(0.73)) }
                .buttonStyle(.plain)
            RoomScrubber(position:player.position,duration:player.duration,seek:player.seek(to:)).padding(.vertical,13)
            HStack(spacing:14) {
                DeckKey(symbol:player.isPlaying ? "pause.fill":"play.fill", label:player.isPlaying ? "暂停":"播放", action:player.toggle)
                Spacer()
                DeckKey(symbol:"backward.end.fill",label:"上一首",action:player.previous)
                DeckKey(symbol:"forward.end.fill",label:"下一首",action:player.next)
            }
        }.foregroundStyle(.white).frame(maxWidth:.infinity,alignment:.leading)
    }

    private func pageTitle(_ p:RoomPage) -> String {
        switch p {case .library:"资料库";case .search:"搜索音乐";case .queue:"播放队列";case .sources:"音乐来源";case .settings:"设置";case .lyrics:"歌词";case .apple:"Apple Music";case .album:"专辑";case .artist:"音乐人"}
    }

    @ViewBuilder private func pageView(_ p:RoomPage) -> some View {
        let route: (RoomPage)->Void = {path.append($0)}
        Group {
            switch p {
            case .sources: RoomSources(palette:palette,go:route)
            case .library: RoomLibrary(importFiles:{importing=true},go:route)
            case .search: RoomSearch(go:route,play:playTrack)
            case .album(let id): RoomAlbumDetail(album:player.albums.first(where:{$0.id==id}),play:playTrack,go:route)
            case .artist(let name): RoomArtist(name:name,go:route,play:playTrack)
            case .queue: RoomQueue(play:playTrack)
            case .lyrics: RoomLyrics()
            case .apple: AppleCatalogView()
            case .settings: RoomSettings(palette:palette,colors:$colors,motion:$motion,go:route,importFiles:{importing=true})
            }
        }
        .foregroundStyle(.white).background(palette.stage.ignoresSafeArea())
    }

    private func playTrack(_ song:RoomTrack,_ sequence:[RoomTrack]) {
        player.play(song,in:sequence);path=[]
    }
}

func rrClock(_ time:Double) -> String {
    let s = Int(max(0,time.isFinite ? time : 0)); return String(format:"%d:%02d",s/60,s%60)
}

private struct RoomScrubber: View {
    let position:Double,duration:Double
    var seek:(Double)->Void
    @State private var pending:Double?
    var body: some View {
        HStack(spacing:10) {
            Text(rrClock(pending ?? position)).monospacedDigit()
            Slider(value:Binding(get:{min(max(0,pending ?? position),max(1,duration))},set:{pending=$0}),in:0...max(1,duration)) { editing in
                if !editing,let value=pending { seek(value);pending=nil }
            }.tint(.white.opacity(0.86)).disabled(duration<=0).accessibilityLabel("播放进度")
            Text(rrClock(duration)).monospacedDigit()
        }.font(.caption).foregroundStyle(.white.opacity(0.73))
    }
}

private struct DeckKey: View {
    let symbol:String,label:String
    var action:()->Void
    var body: some View {
        Button(action:action){
            VStack(spacing:6){
                Capsule()
                    .fill(LinearGradient(colors:[Color(white:0.21),Color(white:0.13),Color(white:0.10)],startPoint:.top,endPoint:.bottom))
                    .frame(width:57,height:25)
                    .overlay(Capsule().strokeBorder(.black.opacity(0.75),lineWidth:1.2))
                    .background(Capsule().fill(.black.opacity(0.32)).padding(-2))
                    .shadow(color:.black.opacity(0.28),radius:2,y:3)
                Image(systemName:symbol).font(.system(size:10,weight:.semibold)).foregroundStyle(.white.opacity(0.88))
            }.frame(minWidth:60,minHeight:48).contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(label)
    }
}

private struct PlayerTools: View {
    let palette:RoomPalette
    var open:(RoomPage)->Void
    @Environment(\.dismiss) private var dismiss
    private let items:[(String,String,RoomPage)] = [
        ("magnifyingglass","搜索",.search),("square.stack","资料库",.library),("list.bullet","队列",.queue),
        ("text.quote","歌词",.lyrics),("music.note.house","音乐来源",.sources),("gearshape","设置",.settings)]
    var body: some View {
        VStack(alignment:.leading,spacing:16) {
            HStack {
                Text("播放工具").font(.system(.title3,design:.serif).weight(.semibold));Spacer()
                Button { dismiss() } label:{Image(systemName:"chevron.down").frame(width:40,height:38)}
                    .buttonStyle(.plain).accessibilityLabel("收起工具")
            }
            LazyVGrid(columns:Array(repeating:GridItem(.flexible(),spacing:8),count:3),spacing:8) {
                ForEach(items.indices,id:\.self) { i in
                    Button {open(items[i].2)} label:{
                        VStack(spacing:6) {Image(systemName:items[i].0);Text(items[i].1).font(.caption)}
                            .frame(maxWidth:.infinity,minHeight:60).background(.white.opacity(0.065),in:RoundedRectangle(cornerRadius:13))
                    }.buttonStyle(.plain)
                }
            }
        }
        .padding(22).frame(maxWidth:.infinity,maxHeight:.infinity)
        .background(palette.stage).foregroundStyle(.white).preferredColorScheme(.dark)
    }
}
