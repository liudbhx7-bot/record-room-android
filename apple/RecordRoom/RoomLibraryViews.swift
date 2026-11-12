import SwiftUI

typealias RoomNavigationAction = (RoomPage) -> Void
typealias RoomPlaybackAction = (RoomTrack,[RoomTrack]) -> Void

private struct RoomSection: View {
    let title:String
    var detail:String=""
    var body:some View {
        HStack(alignment:.lastTextBaseline) {
            Text(title).font(.system(.headline,design:.serif));Spacer()
            if !detail.isEmpty {Text(detail).font(.caption).foregroundStyle(.secondary)}
        }.padding(.top,12)
    }
}

struct RoomColumn<Content:View>: View {
    var maxWidth:CGFloat = 690
    @ViewBuilder var content:()->Content
    var body: some View {
        ScrollView {
            VStack(alignment:.leading,spacing:15,content:content)
                .padding(.horizontal,22).padding(.top,16).padding(.bottom,30)
                .frame(maxWidth:maxWidth,alignment:.leading).frame(maxWidth:.infinity)
        }
    }
}

private struct SourceRow: View {
    let title:String,icon:String
    var detail:String=""
    var outward=false
    var body:some View {
        HStack(spacing:13) {
            Image(systemName:icon).font(.system(size:14,weight:.medium))
                .frame(width:32,height:32).background(.white.opacity(0.075),in:RoundedRectangle(cornerRadius:9))
            Text(title).font(.headline);Spacer(minLength:6)
            if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(.secondary) }
            Image(systemName: outward ? "arrow.up.right":"chevron.right").font(.caption2).foregroundStyle(.secondary)
        }.frame(minHeight:46).contentShape(Rectangle())
    }
}

struct RoomSources: View {
    @EnvironmentObject private var player:LibraryPlayer
    let palette:RoomPalette
    var go:RoomNavigationAction
    private var sourceName:String { player.playSourceTitle ?? "唱片机资料库" }
    var body:some View {
        RoomColumn {
            Text("音乐来源").font(.system(size:35,weight:.semibold,design:.serif))
            ZStack(alignment:.leading) {
                RoundedRectangle(cornerRadius:14).fill(LinearGradient(colors:[palette.surface,palette.tint.opacity(0.34)],startPoint:.leading,endPoint:.trailing))
                GeometryReader { g in
                    let side = min(116,g.size.width*0.29)
                    Circle().fill(AngularGradient(colors:[.black,Color(white:0.26),.black, Color(white:0.13),.black],center:.center))
                        .frame(width:side,height:side).position(x:g.size.width-side*0.50,y:g.size.height*0.54)
                    RoomCover(url:player.current?.artworkURL).frame(width:side,height:side)
                        .overlay(Rectangle().strokeBorder(.white.opacity(0.18)))
                        .shadow(color:.black.opacity(0.4),radius:4,x:2,y:5)
                        .rotationEffect(.degrees(-9))
                        .position(x:g.size.width-side*0.78,y:g.size.height*0.55)
                }.clipShape(RoundedRectangle(cornerRadius:14))
                VStack(alignment:.leading,spacing:8) {Text("当前来源").font(.caption).foregroundStyle(.white.opacity(0.68));Text(sourceName).font(.system(.title3,design:.serif).weight(.semibold)).lineLimit(2)}
                    .padding(.leading,18).padding(.trailing,134)
            }.frame(height:134)
            RoomSection(title:"我的资料库")
            Button {go(.library)} label:{
                #if os(macOS)
                SourceRow(title:"本机音乐",icon:"externaldrive",detail:player.playSourceTitle == nil ? "使用中":"")
                #else
                SourceRow(title:"手机音乐",icon:"music.note.list",detail:player.playSourceTitle == nil ? "使用中":"")
                #endif
            }.buttonStyle(.plain)
            Divider()
            RoomSection(title:"账号与曲库")
            Button {go(.apple)} label:{SourceRow(title:"Apple Music",icon:"music.note",detail:"查找音乐")}
                .buttonStyle(.plain)
            Divider()
            RoomSection(title:"官方音乐服务",detail:"前往官方页面")
            ForEach([("网易云音乐","https://music.163.com","cloud"),("QQ 音乐","https://y.qq.com","music.note"),("汽水音乐","https://www.douyin.com/qishui","waveform")],id:\.0){ value in
                if let url=URL(string:value.1) {
                    Link(destination:url) {SourceRow(title:value.0,icon:value.2,outward:true)}.buttonStyle(.plain)
                    Divider()
                }
            }
        }.accessibilityIdentifier("sources.catalog")
    }
}

struct RoomLibrary: View {
    @EnvironmentObject private var player:LibraryPlayer
    var importFiles:()->Void
    var go:RoomNavigationAction
    @State private var shelves=false
    var body:some View {
        RoomColumn(maxWidth:900) {
            HStack {
                Text("唱片资料库").font(.system(size:33,weight:.semibold,design:.serif))
                Spacer()
                Button {importFiles()} label:{Image(systemName:"plus").frame(width:44,height:44)}.buttonStyle(.plain)
                    .accessibilityLabel("导入本地音乐").disabled(player.importing)
            }
            if player.importing {ProgressView("正在导入音乐")}
            RoomSection(title:"我的专辑",detail:"\(player.albums.count) 张")
            if player.albums.isEmpty {Button("导入本地音乐",action:importFiles)}
            if !player.albums.isEmpty {
                Picker("浏览方式",selection:$shelves) {Text("封面").tag(false);Text("唱片架").tag(true)}
                    .pickerStyle(.segmented)
                if shelves {RoomSleeves(albums:player.albums){go(.album($0))}}
                else {AlbumGrid(albums:player.albums,go:go)}
            }
        }
    }
}

private struct AlbumGrid:View {
    let albums:[RoomAlbum]
    var go:RoomNavigationAction
    var body:some View {
        LazyVGrid(columns:[GridItem(.adaptive(minimum:128,maximum:224),spacing:16)],alignment:.leading,spacing:21) {
            ForEach(albums) { album in
                Button { go(.album(album.id)) } label:{
                    VStack(alignment:.leading,spacing:5) {
                        RoomCover(url:album.artworkURL).aspectRatio(1,contentMode:.fit)
                            .clipShape(RoundedRectangle(cornerRadius:4))
                            .overlay(RoundedRectangle(cornerRadius:4).strokeBorder(.white.opacity(0.11)))
                        Text(album.title).font(.headline).lineLimit(1)
                        Text(album.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }.multilineTextAlignment(.leading)
                }.buttonStyle(.plain)
            }
        }
    }
}

private struct SongRows:View {
    @EnvironmentObject private var player:LibraryPlayer
    let tracks:[RoomTrack]
    var numbered = false
    var play:RoomPlaybackAction
    var body:some View {
        LazyVStack(spacing:0) {
            ForEach(Array(tracks.enumerated()),id:\.element.id) { index,song in
                Button {play(song,tracks)} label:{
                    HStack(spacing:13) {
                        if numbered {Text(String(format:"%02d",index+1)).font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width:26)}
                        else {RoomCover(url:song.artworkURL).frame(width:44,height:44).clipShape(RoundedRectangle(cornerRadius:4))}
                        VStack(alignment:.leading,spacing:4){
                            Text(song.title).font(.body.weight(.medium)).lineLimit(1)
                            Text(song.artist).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }.multilineTextAlignment(.leading)
                        Spacer()
                        if player.current?.id == song.id {Image(systemName:player.isPlaying ? "waveform":"record.circle").accessibilityLabel("当前曲目")}
                        if song.duration>0 {Text(rrClock(song.duration)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)}
                    }.padding(.vertical,8).frame(maxWidth:.infinity,alignment:.leading).contentShape(Rectangle())
                }.buttonStyle(.plain)
                Divider()
            }
        }
    }
}

struct RoomAlbumDetail: View {
    let album:RoomAlbum?
    var play:RoomPlaybackAction
    var go:RoomNavigationAction
    var body:some View {
        if let album {
            RoomColumn {
                RoomCover(url:album.artworkURL,fit:true).aspectRatio(1,contentMode:.fit).frame(maxWidth:330)
                    .shadow(color:.black.opacity(0.32),radius:12,y:6)
                    .frame(maxWidth:.infinity).padding(.vertical,8)
                Text(album.title).font(.system(size:30,weight:.semibold,design:.serif))
                Button {go(.artist(album.artist))} label:{Label(album.artist,systemImage:"person")}.buttonStyle(.plain)
                HStack{
                    Text("\(album.tracks.count) 首歌曲").font(.caption).foregroundStyle(.secondary);Spacer()
                    if let first=album.tracks.first { Button {play(first,album.tracks)} label:{ Label("播放专辑",systemImage:"play.fill") }.buttonStyle(.bordered) }
                }
                SongRows(tracks:album.tracks,numbered:true,play:play)
            }
        } else {
            ContentUnavailableView("专辑不在本机资料库中",systemImage:"square.stack",description:Text("可在音乐来源中查看在线曲库。"))
        }
    }
}

struct RoomArtist: View {
    @EnvironmentObject private var player:LibraryPlayer
    let name:String
    var go:RoomNavigationAction
    var play:RoomPlaybackAction
    @State private var chapter=0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body:some View {
        let songs=player.tracks.filter{$0.artist == name || $0.albumArtist == name}
        let albums=player.albums.filter{$0.tracks.contains{song in song.artist == name || song.albumArtist == name}}
        RoomColumn {
            Text(name).font(.system(size:34,weight:.semibold,design:.serif))
            Picker("音乐人章节",selection:$chapter) {Text("介绍").tag(0);Text("歌曲").tag(1);Text("专辑").tag(2)}
                .pickerStyle(.segmented)
            VStack(alignment:.leading,spacing:14) {
                switch chapter {
                case 0:
                    if let first=albums.first {
                        RoomCover(url:first.artworkURL,fit:true).aspectRatio(1,contentMode:.fit)
                            .frame(maxWidth:245).frame(maxWidth:.infinity)
                    }
                    Text("资料库收录了 "+name+" 的 \(albums.count) 张专辑、\(songs.count) 首歌。").foregroundStyle(.secondary)
                    RoomSection(title:"从这几首听起")
                    SongRows(tracks:Array(songs.prefix(5)),play:play)
                case 1: RoomSection(title:"歌曲",detail:"\(songs.count) 首");SongRows(tracks:songs,numbered:true,play:play)
                default: RoomSleeves(albums:albums){go(.album($0))}
                }
            }
            .id(chapter).transition(.opacity.combined(with:.offset(y:8)))
            .animation(reduceMotion ? nil:.easeOut(duration:0.28),value:chapter)
        }
    }
}

struct RoomSearch: View {
    @EnvironmentObject private var player:LibraryPlayer
    @State private var query=""
    var go:RoomNavigationAction
    var play:RoomPlaybackAction
    var body:some View {
        let key=query.trimmingCharacters(in:.whitespacesAndNewlines)
        let tracks=player.tracks.filter { $0.title.localizedCaseInsensitiveContains(key) || $0.artist.localizedCaseInsensitiveContains(key) || $0.album.localizedCaseInsensitiveContains(key) }
        let albums=player.albums.filter{$0.title.localizedCaseInsensitiveContains(key)}
        let artists=Array(Set(tracks.map(\.artist).filter{$0.localizedCaseInsensitiveContains(key)})).sorted()
        RoomColumn {
            Text("搜索音乐").font(.system(size:34,weight:.semibold,design:.serif))
            HStack {
                Image(systemName:"magnifyingglass").foregroundStyle(.secondary)
                TextField("歌曲、专辑或音乐人",text:$query).textFieldStyle(.plain)
                if !query.isEmpty {Button {query=""} label:{Image(systemName:"xmark.circle.fill")}.buttonStyle(.plain).accessibilityLabel("清除搜索")}
            }.padding(13).background(.white.opacity(0.075),in:RoundedRectangle(cornerRadius:14))
            if key.isEmpty {
                RoomSection(title:"翻看专辑",detail:"本机资料库")
                AlbumGrid(albums:player.albums,go:go)
            } else {
                if !artists.isEmpty {
                    RoomSection(title:"音乐人")
                    ForEach(artists,id:\.self){ name in Button {go(.artist(name))}label:{SourceRow(title:name,icon:"person")}.buttonStyle(.plain)}
                }
                if !albums.isEmpty {RoomSection(title:"专辑");AlbumGrid(albums:albums,go:go)}
                if !tracks.isEmpty {RoomSection(title:"歌曲",detail:"\(tracks.count) 首");SongRows(tracks:tracks,play:play)}
                if tracks.isEmpty && albums.isEmpty {Text("本机资料库未找到匹配内容").foregroundStyle(.secondary)}
            }
            Divider()
            Button {go(.apple)} label:{SourceRow(title:"搜索 Apple Music",icon:"magnifyingglass")}.buttonStyle(.plain)
        }
    }
}

struct RoomQueue:View {
    @EnvironmentObject private var player:LibraryPlayer
    var play:RoomPlaybackAction
    var body:some View {
        RoomColumn {RoomSection(title:"播放队列",detail:"\(player.playbackQueue.count) 首");SongRows(tracks:player.playbackQueue,play:play)}
    }
}

struct RoomSettings: View {
    @EnvironmentObject private var player:LibraryPlayer
    let palette:RoomPalette
    @Binding var colors:Bool
    @Binding var motion:Bool
    var go:RoomNavigationAction
    var importFiles:()->Void
    var body:some View {
        RoomColumn {
            Text("设置").font(.system(size:34,weight:.semibold,design:.serif))
            RoomSection(title:"音乐与连接")
            Button {go(.sources)} label:{SourceRow(title:"音乐来源",icon:"music.note.house")}.buttonStyle(.plain)
            Button(action:importFiles) {SourceRow(title:player.importing ? "正在导入":"导入本地音乐",icon:"square.and.arrow.down")}.buttonStyle(.plain).disabled(player.importing)
            Divider()
            RoomSection(title:"唱机外观")
            Toggle("随封面自动配色",isOn:$colors)
            Toggle("唱片与唱臂动画",isOn:$motion)
            Divider()
            RoomSection(title:"聆听体验")
            Toggle("落针音效",isOn:$player.needleEnabled)
            Divider()
            LabeledContent("版本",value:Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "预览版").font(.caption).foregroundStyle(.secondary)
        }.toggleStyle(.switch)
    }
}
