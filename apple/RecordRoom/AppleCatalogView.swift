import SwiftUI
#if os(iOS)
import MediaPlayer
#endif

/// Apple's public directory links each listing to its full page in Music.
/// Selected device-library files use the same listening controls as imported audio.
struct AppleCatalogView: View {
    @EnvironmentObject private var player: LibraryPlayer
    @State private var query = ""
    @State private var results: [StoreSong] = []
    @State private var notice = ""
    @State private var loading = false
    @State private var request: Task<Void, Never>?
    #if os(iOS)
    @State private var picker = false
    #endif

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 19) {
                Text("Apple Music").font(.largeTitle.weight(.semibold))
                #if os(iOS)
                Button { chooseDeviceSong() } label: {
                    Label("从系统音乐资料库选歌", systemImage: "music.note.list")
                        .frame(maxWidth: .infinity, alignment: .leading)
                }.buttonStyle(.bordered).controlSize(.large)
                #endif
                HStack {
                    TextField("歌名或音乐人", text: $query).textFieldStyle(.roundedBorder)
                        .onSubmit(search)
                    Button("查找", action: search).disabled(query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                if loading { ProgressView("正在查找") }
                if !notice.isEmpty { Text(notice).font(.callout).foregroundStyle(.secondary) }
                ForEach(results) { item in
                    Link(destination: item.trackViewUrl) {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.trackName).font(.headline)
                                Text([item.artistName,item.collectionName].compactMap { $0 }.joined(separator: " · "))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "arrow.up.right").accessibilityLabel("在 Apple Music 打开")
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain)
                    Divider()
                }
                Link("打开 Apple Music ↗", destination: URL(string: "https://music.apple.com/")!)
            }.padding(22).frame(maxWidth: 660, alignment: .leading)
        }
        .onDisappear { request?.cancel() }
        #if os(iOS)
        .sheet(isPresented: $picker) {
            DeviceMusicPicker { item in
                picker = false
                guard let item else { return }
                guard let url = item.assetURL, !item.hasProtectedAsset else {
                    notice = "所选曲目需要在系统「音乐」App 中播放。"
                    return
                }
                player.playPreview(title: item.title ?? "未知歌曲", artist: item.artist ?? "未知音乐人",
                                   album: item.albumTitle ?? "未知专辑",
                                   artworkURL: deviceCover(item), audioURL: url)
                notice = "正在唱片机播放「"+(item.title ?? "所选歌曲")+"」。"
            }
        }
        #endif
    }

    private func search() {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return }
        request?.cancel()
        var parts = URLComponents(string: "https://itunes.apple.com/search")!
        parts.queryItems = [URLQueryItem(name: "term", value: term),
                            URLQueryItem(name: "country", value: Locale.current.region?.identifier ?? "US"),
                            URLQueryItem(name: "entity", value: "song"), URLQueryItem(name: "limit", value: "25")]
        guard let url=parts.url else { return }
        results=[]; notice=""; loading=true
        request = Task { @MainActor in
            do {
                let (data, response) = try await URLSession.shared.data(from: url)
                try Task.checkCancellation()
                guard let http=response as? HTTPURLResponse, http.statusCode == 200 else { throw URLError(.badServerResponse) }
                results = try JSONDecoder().decode(StoreResults.self,from:data).results.filter { $0.trackViewUrl.scheme == "https" }
                notice = results.isEmpty ? "没有找到，试试歌名或另一种写法。" : "选择结果可在 Apple Music 打开。"
            } catch {
                guard !Task.isCancelled else { return }
                notice = "暂时无法读取目录，可重试或打开 Apple Music。"
            }
            loading=false
        }
    }
    #if os(iOS)
    private func chooseDeviceSong() {
        #if targetEnvironment(simulator)
        notice = "系统音乐资料库需要在 iPhone 真机授权。你可以从资料库导入音频文件。"
        #else
        MPMediaLibrary.requestAuthorization { status in
            DispatchQueue.main.async {
                if status == .authorized { picker = true }
                else { notice = "需要音乐资料库访问权限。你也可以通过「文件」导入。" }
            }
        }
        #endif
    }
    private func deviceCover(_ item: MPMediaItem) -> URL? {
        guard let bytes=item.artwork?.image(at: CGSize(width: 600,height: 600))?.jpegData(compressionQuality: 0.88),
              let folder=FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        let path=folder.appendingPathComponent("music-art-\(item.albumPersistentID)-\(item.persistentID).jpg")
        do { try bytes.write(to: path,options:.atomic);return path } catch { return nil }
    }
    #endif
}
private struct StoreResults: Decodable { let results: [StoreSong] }
private struct StoreSong: Identifiable,Decodable {
    let trackId: Int64
    let trackName, artistName: String
    let collectionName: String?
    let trackViewUrl: URL
    var id: Int64 { trackId }
}
#if os(iOS)
private struct DeviceMusicPicker: UIViewControllerRepresentable {
    var picked: (MPMediaItem?) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(picked) }
    func makeUIViewController(context: Context) -> MPMediaPickerController {
        let picker=MPMediaPickerController(mediaTypes:.music)
        picker.showsCloudItems=false
        picker.delegate=context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: MPMediaPickerController, context: Context) {}
    final class Coordinator: NSObject, MPMediaPickerControllerDelegate {
        let picked: (MPMediaItem?) -> Void
        init(_ picked: @escaping (MPMediaItem?) -> Void) { self.picked=picked }
        func mediaPicker(_ mediaPicker: MPMediaPickerController, didPickMediaItems collection: MPMediaItemCollection) { picked(collection.items.first) }
        func mediaPickerDidCancel(_ mediaPicker: MPMediaPickerController) { picked(nil) }
    }
}
#endif
