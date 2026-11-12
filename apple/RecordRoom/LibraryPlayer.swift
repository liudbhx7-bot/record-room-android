import AVFoundation
import Combine
import CryptoKit
import Foundation
import ImageIO
import MediaPlayer
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
private typealias CoverImage = UIImage
#else
import AppKit
private typealias CoverImage = NSImage
#endif

struct RoomTrack: Hashable, Identifiable, Sendable {
    let id, title, artist, album, albumArtist: String
    let duration: Double
    let trackNumber: Int
    let audioURL: URL
    let artworkURL: URL?

    var albumKey: String {
        albumArtist.lowercased() + "\u{1f}" + album.lowercased()
    }
}

struct RoomAlbum: Identifiable {
    let id, title, artist: String
    let tracks: [RoomTrack]
    var artworkURL: URL? { tracks.lazy.compactMap(\.artworkURL).first }
}

// Keep relative names on disk. iOS may relocate the app container during an update.
private struct ImportedTrack: Codable, Sendable {
    let id, title, artist, album, albumArtist, filename: String
    let duration: Double
    let trackNumber: Int
    let hasCover: Bool

    func resolved(in media: URL) -> RoomTrack {
        let directory = media.appendingPathComponent(id, isDirectory: true)
        return RoomTrack(id: id, title: title, artist: artist, album: album, albumArtist: albumArtist,
                         duration: duration, trackNumber: trackNumber, audioURL: directory.appendingPathComponent(filename),
                         artworkURL: hasCover ? directory.appendingPathComponent("cover.jpg") : nil)
    }
}

private struct DemoSong: Decodable {
    let id, title, artist, album, uri, artworkUri: String
    let durationMs, trackNumber: Int
}

private enum ImportFailure: LocalizedError {
    case invalidAudio, unreadable, libraryDamaged
    var errorDescription: String? {
        switch self {
        case .invalidAudio: "系统无法解码这份音频"
        case .unreadable: "无法读取文件"
        case .libraryDamaged: "资料库索引无法读取，请先备份应用数据"
        }
    }
}

private enum AudioImport {
    static func prepare(_ source: URL, in media: URL) async throws -> ImportedTrack {
        let fm = FileManager.default
        let staging = media.appendingPathComponent(".import-" + UUID().uuidString, isDirectory: true)
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: staging) }
        let ext = source.pathExtension
        let filename = ext.isEmpty ? "audio" : "audio." + ext
        let audio = staging.appendingPathComponent(filename)
        let access = source.startAccessingSecurityScopedResource()
        defer { if access { source.stopAccessingSecurityScopedResource() } }
        var coordinatedError: NSError?
        var copyError: Error?
        NSFileCoordinator().coordinate(readingItemAt: source, options: [], error: &coordinatedError) { coordinated in
            do { try fm.copyItem(at: coordinated, to: audio) } catch { copyError = error }
        }
        if let coordinatedError { throw coordinatedError }
        if let copyError { throw copyError }
        let handle = try FileHandle(forReadingFrom: audio)
        defer { try? handle.close() }
        var digest = SHA256()
        while let block = try handle.read(upToCount: 1_048_576), !block.isEmpty {
            try Task.checkCancellation()
            digest.update(data: block)
        }
        let id = digest.finalize().map { String(format: "%02x", $0) }.joined()
        // Probe the audio file before publishing its index entry.
        let probe = try AVAudioPlayer(contentsOf: audio)
        let duration = probe.duration
        guard duration.isFinite, duration > 0 else { throw ImportFailure.invalidAudio }
        let asset = AVURLAsset(url: audio)
        let common = (try? await asset.load(.commonMetadata)) ?? []
        let all = (try? await asset.load(.metadata)) ?? []
        func string(_ item: AVMetadataItem?) async -> String? {
            let value = try? await item?.load(.stringValue)?.trimmingCharacters(in: .whitespacesAndNewlines)
            return value.flatMap { $0.isEmpty ? nil : $0 }
        }
        func commonItem(_ key: AVMetadataKey) -> AVMetadataItem? {
            AVMetadataItem.metadataItems(from: common, withKey: key, keySpace: .common).first
        }
        let title = await string(commonItem(.commonKeyTitle)) ?? source.deletingPathExtension().lastPathComponent
        let artist = await string(commonItem(.commonKeyArtist)) ?? "未知音乐人"
        let album = await string(commonItem(.commonKeyAlbumName)) ?? "未分类专辑"
        let albumArtistItem = all.first { $0.identifier == .iTunesMetadataAlbumArtist || $0.identifier == .id3MetadataBand }
        let albumArtist = await string(albumArtistItem) ?? artist
        let trackItem = all.first { $0.identifier == .id3MetadataTrackNumber || $0.identifier == .iTunesMetadataTrackNumber }
        var number = Int((await string(trackItem) ?? "").split(separator: "/").first ?? "") ?? 0
        if number == 0, trackItem?.identifier == .iTunesMetadataTrackNumber,
           let binary = try? await trackItem?.load(.dataValue), binary.count >= 4 {
            number = Int(binary[2]) << 8 | Int(binary[3])
        }
        var hasCover = false
        if let item = commonItem(.commonKeyArtwork), let data = try? await item.load(.dataValue),
           let source = CGImageSourceCreateWithData(data as CFData, nil),
           let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 1024
           ] as CFDictionary),
           let writer = CGImageDestinationCreateWithURL(staging.appendingPathComponent("cover.jpg") as CFURL, UTType.jpeg.identifier as CFString, 1, nil) {
            CGImageDestinationAddImage(writer, image, [kCGImageDestinationLossyCompressionQuality: 0.92] as CFDictionary)
            hasCover = CGImageDestinationFinalize(writer)
        }
        let destination = media.appendingPathComponent(id, isDirectory: true)
        // Reuse a completed copy after a duplicate import or an interrupted index write.
        if !fm.fileExists(atPath: destination.path) {
            try fm.moveItem(at: staging, to: destination)
        }
        let existing = try fm.contentsOfDirectory(at: destination, includingPropertiesForKeys: nil)
        guard let file = existing.first(where: { $0.deletingPathExtension().lastPathComponent == "audio" }) else {
            throw ImportFailure.unreadable
        }
        return ImportedTrack(id: id, title: title, artist: artist, album: album, albumArtist: albumArtist,
                             filename: file.lastPathComponent, duration: duration, trackNumber: number,
                             hasCover: hasCover || existing.contains { $0.lastPathComponent == "cover.jpg" })
    }
}

@MainActor
final class LibraryPlayer: NSObject, ObservableObject {
    @Published private(set) var tracks: [RoomTrack] = []
    @Published private(set) var current: RoomTrack?
    @Published private(set) var isPlaying = false
    @Published private(set) var position = 0.0
    @Published private(set) var duration = 0.0
    @Published private(set) var importing = false
    @Published var errorMessage: String?
    @Published var needleEnabled: Bool { didSet { defaults.set(needleEnabled, forKey: "needleEnabled") } }

    var playbackQueue: [RoomTrack] { ordering }
    var playSourceTitle: String? {
        guard let current, current.id.hasPrefix("stream-") else { return nil }
        return current.audioURL.scheme == "ipod-library" ? "设备音乐资料库" : "Apple Music 试听"
    }
    var albums: [RoomAlbum] {
        var order = [String]()
        var grouped = [String: [RoomTrack]]()
        for track in tracks {
            if grouped[track.albumKey] == nil { order.append(track.albumKey) }
            grouped[track.albumKey, default: []].append(track)
        }
        return order.compactMap { key in
            guard let songs = grouped[key], let first = songs.first else { return nil }
            let ordered = songs.sorted {
                if $0.trackNumber > 0 && $1.trackNumber > 0 && $0.trackNumber != $1.trackNumber { return $0.trackNumber < $1.trackNumber }
                if ($0.trackNumber > 0) != ($1.trackNumber > 0) { return $0.trackNumber > 0 }
                return $0.title.localizedStandardCompare($1.title) == .orderedAscending
            }
            return RoomAlbum(id: key, title: first.album, artist: first.albumArtist, tracks: ordered)
        }
    }

    private let folder: URL
    private var media: URL { folder.appendingPathComponent("Media", isDirectory: true) }
    private var index: URL { folder.appendingPathComponent("library.json") }
    private let bundle: Bundle
    private let defaults: UserDefaults
    private var records = [ImportedTrack]()
    private var indexReadable = true
    private var ordering = [RoomTrack]()
    private var audio: AVPlayer?
    private var statusObserver: NSKeyValueObservation?
    private var completionObserver: NSObjectProtocol?
    private var failureObserver: NSObjectProtocol?
    private var needle: AVAudioPlayer?
    private var ticker: Timer?
    private var importTail: Task<Void, Never>?
    private var jobs = 0
    private var lastCheckpoint = 0.0
    private var remoteTargets: [(MPRemoteCommand, Any)] = []
    private var artworkKey: URL?
    private var nowArtwork: MPMediaItemArtwork?
    private var interruptions: [NSObjectProtocol] = []
    #if os(iOS)
    private var interrupted = false
    #endif

    init(folder: URL? = nil, bundle: Bundle = .main, defaults: UserDefaults = .standard) {
        self.folder = folder ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("RecordRoom", isDirectory: true)
        self.bundle = bundle
        self.defaults = defaults
        self.needleEnabled = defaults.object(forKey: "needleEnabled") as? Bool ?? true
        super.init()
        restoreLibrary()
        configureRemoteControls()
        configureInterruptions()
    }

    private func resource(_ name: String) -> URL? {
        let basename = (name as NSString).lastPathComponent
        return bundle.url(forResource: basename, withExtension: nil)
            ?? bundle.url(forResource: basename, withExtension: nil, subdirectory: "demo")
    }

    private func restoreLibrary() {
        if FileManager.default.fileExists(atPath: index.path) {
            do { records = try JSONDecoder().decode([ImportedTrack].self, from: Data(contentsOf: index)) }
            catch { indexReadable = false; errorMessage = ImportFailure.libraryDamaged.localizedDescription }
        }
        var demos: [RoomTrack] = []
        if let catalog = resource("catalog.json"), let data = try? Data(contentsOf: catalog),
           let items = try? JSONDecoder().decode([DemoSong].self, from: data) {
            demos = items.compactMap { song in
                guard let file = resource(song.uri) else { return nil }
                return RoomTrack(id: song.id, title: song.title, artist: song.artist, album: song.album, albumArtist: song.artist,
                                 duration: Double(song.durationMs)/1000, trackNumber: song.trackNumber,
                                 audioURL: file, artworkURL: resource(song.artworkUri))
            }
        }
        tracks = records.map { $0.resolved(in: media) } + demos
        let lookup = Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        ordering = defaults.stringArray(forKey: "queue")?.compactMap { lookup[$0] } ?? []
        current = defaults.string(forKey: "lastTrack").flatMap { lookup[$0] } ?? tracks.first
        if ordering.isEmpty || !ordering.contains(where: { $0.id == current?.id }) { ordering = tracks }
        duration = current?.duration ?? 0
        if defaults.string(forKey: "lastTrack") == current?.id {
            position = min(max(0, defaults.double(forKey: "lastPosition")), duration)
        }
    }

    /// Serialize multi-file picker and document-open submissions so each index commit includes the preceding one.
    func importFiles(_ urls: [URL]) async {
        guard !urls.isEmpty else { return }
        jobs += 1
        importing = true
        let previous = importTail
        let task = Task {
            await previous?.value
            await performImport(urls)
        }
        importTail = task
        await task.value
        jobs -= 1
        if jobs == 0 { importing = false; importTail = nil }
    }

    private func performImport(_ urls: [URL]) async {
        guard indexReadable else { errorMessage = ImportFailure.libraryDamaged.localizedDescription; return }
        var failures = [String]()
        let destination = media
        for url in urls {
            do {
                let record = try await Task.detached(priority: .utility) {
                    try await AudioImport.prepare(url, in: destination)
                }.value
                guard !records.contains(where: { $0.id == record.id }) else { continue }
                let updated = records + [record]
                try JSONEncoder().encode(updated).write(to: index, options: .atomic)
                records = updated
                let track = record.resolved(in: media)
                let followsLibrary = ordering == tracks
                tracks.insert(track, at: tracks.firstIndex { $0.id.hasPrefix("demo-") } ?? tracks.endIndex)
                if followsLibrary { ordering = tracks }
                if current == nil { select(track) }
            } catch { failures.append(url.lastPathComponent + " · " + error.localizedDescription) }
        }
        if !failures.isEmpty { errorMessage = failures.joined(separator: "\n") }
    }

    func play(_ track: RoomTrack, in queue: [RoomTrack]? = nil) {
        if let queue, queue.contains(track) { ordering = queue } else { ordering = tracks }
        if !ordering.contains(track) { ordering = [track] }
        select(track)
        start()
    }

    private func select(_ track: RoomTrack) {
        stopTicker()
        resetAudio()
        isPlaying = false
        current = track
        duration = track.duration
        position = 0
        checkpoint()
        updateNowPlaying()
    }

    func toggle() {
        if isPlaying { pause() } else { start() }
    }

    func pause() {
        #if os(iOS)
        interrupted = false
        #endif
        audio?.pause()
        if let seconds = audio?.currentTime().seconds, seconds.isFinite { position = seconds }
        isPlaying = false
        stopTicker()
        checkpoint()
        updateNowPlaying()
    }

    /// The catalog preview or a selected iOS Music Library item is transient.
    func playPreview(title: String, artist: String, album: String, artworkURL: URL?, audioURL: URL) {
        let streamed = RoomTrack(id: "stream-" + UUID().uuidString, title: title, artist: artist, album: album,
                                 albumArtist: artist, duration: 0, trackNumber: 0, audioURL: audioURL, artworkURL: artworkURL)
        ordering = [streamed]
        select(streamed)
        start()
    }

    private func resetAudio() {
        statusObserver = nil
        for observer in [completionObserver, failureObserver].compactMap({ $0 }) {
            NotificationCenter.default.removeObserver(observer)
        }
        completionObserver = nil
        failureObserver = nil
        audio?.pause()
        audio = nil
    }

    private func attach(_ track: RoomTrack) {
        let item = AVPlayerItem(url: track.audioURL)
        let player = AVPlayer(playerItem: item)
        audio = player
        statusObserver = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self, self.audio?.currentItem === item else { return }
                if item.status == .failed {
                    self.failed(item.error)
                } else if item.status == .readyToPlay {
                    let value = item.duration.seconds
                    if value.isFinite && value > 0 { self.duration = value }
                    self.updateNowPlaying()
                }
            }
        }
        completionObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.audio?.currentItem === item else { return }
                if let i = self.ordering.firstIndex(where: { $0.id == self.current?.id }), i+1 < self.ordering.count {
                    self.step(by: 1)
                } else {
                    self.pause()
                    self.seek(to: 0)
                }
            }
        }
        failureObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] note in
            let message = (note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)?.localizedDescription
            Task { @MainActor in
                guard let self, self.audio?.currentItem === item else { return }
                self.failed(message.map { NSError(domain: "RecordRoom", code: 1, userInfo: [NSLocalizedDescriptionKey: $0]) } ?? item.error)
            }
        }
    }

    private func failed(_ error: Error?) {
        let title = current?.title ?? "歌曲"
        resetAudio()
        isPlaying = false
        stopTicker()
        updateNowPlaying()
        errorMessage = "无法播放「" + title + "」 · " + (error?.localizedDescription ?? "系统无法读取音源")
    }

    private func start() {
        guard !isPlaying, let track = current else { return }
        #if os(iOS)
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
        } catch { failed(error); return }
        #endif
        if audio == nil {
            attach(track)
            if position > 0 { seek(to: position) }
        } else if position >= duration && duration > 0 {
            seek(to: 0)
        }
        audio?.play()
        if needleEnabled, let url = resource("needle.wav") {
            if needle == nil { needle = try? AVAudioPlayer(contentsOf: url) }
            needle?.currentTime = 0
            needle?.play()
        }
        isPlaying = true
        startTicker()
        updateNowPlaying()
        checkpoint()
    }

    func next() { step(by: 1) }
    func previous() {
        if position > 3 { seek(to: 0) } else { step(by: -1) }
    }

    private func step(by offset: Int) {
        guard !ordering.isEmpty, let current,
              let i = ordering.firstIndex(where: { $0.id == current.id }) else { return }
        select(ordering[(i + offset + ordering.count) % ordering.count])
        start()
    }

    func seek(to seconds: Double) {
        guard seconds.isFinite else { return }
        position = max(0, min(duration, seconds))
        audio?.seek(to: CMTime(seconds: position, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero,
                    completionHandler: { _ in })
        checkpoint()
        updateNowPlaying()
    }

    private func startTicker() {
        stopTicker()
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.isPlaying, let audio = self.audio else { return }
                let seconds = audio.currentTime().seconds
                guard seconds.isFinite else { return }
                self.position = seconds
                if abs(self.position-self.lastCheckpoint) >= 5 { self.checkpoint() }
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        ticker = timer
    }

    private func stopTicker() { ticker?.invalidate(); ticker = nil }
    private func checkpoint() {
        guard let current, !current.id.hasPrefix("stream-") else { return }
        defaults.set(current.id, forKey: "lastTrack")
        defaults.set(position, forKey: "lastPosition")
        let ids = ordering.map(\.id)
        if defaults.stringArray(forKey: "queue") != ids { defaults.set(ids, forKey: "queue") }
        lastCheckpoint = position
    }

    private func configureRemoteControls() {
        let center = MPRemoteCommandCenter.shared()
        func add(_ command: MPRemoteCommand, _ perform: @escaping @MainActor (LibraryPlayer) -> Void) {
            command.isEnabled = true
            let token = command.addTarget { [weak self] _ in
                Task { @MainActor in if let self { perform(self) } }
                return .success
            }
            remoteTargets.append((command, token))
        }
        add(center.playCommand) { $0.start() }
        add(center.pauseCommand) { $0.pause() }
        add(center.togglePlayPauseCommand) { $0.toggle() }
        add(center.nextTrackCommand) { $0.next() }
        add(center.previousTrackCommand) { $0.previous() }
        center.changePlaybackPositionCommand.isEnabled = true
        let token = center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let change = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let value = change.positionTime
            Task { @MainActor in self?.seek(to: value) }
            return .success
        }
        remoteTargets.append((center.changePlaybackPositionCommand, token))
    }

    private func updateNowPlaying() {
        let info = MPNowPlayingInfoCenter.default()
        guard let track = current else { info.nowPlayingInfo = nil; return }
        if artworkKey != track.artworkURL {
            artworkKey = track.artworkURL
            nowArtwork = nil
            if let url = artworkKey, url.isFileURL, let cover = CoverImage(contentsOfFile: url.path) {
                nowArtwork = MPMediaItemArtwork(boundsSize: cover.size) { _ in cover }
            }
        }
        var fields: [String: Any] = [
            MPMediaItemPropertyTitle: track.title, MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.album, MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position, MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0
        ]
        if let nowArtwork { fields[MPMediaItemPropertyArtwork] = nowArtwork }
        info.nowPlayingInfo = fields
        #if os(macOS)
        info.playbackState = isPlaying ? .playing : .paused
        #endif
    }

    private func configureInterruptions() {
        #if os(iOS)
        let center = NotificationCenter.default
        interruptions.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let rawOptions = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            Task { @MainActor in
                guard let self else { return }
                if type == AVAudioSession.InterruptionType.began.rawValue {
                    let shouldResume = self.isPlaying
                    self.pause(); self.interrupted = shouldResume
                } else if type == AVAudioSession.InterruptionType.ended.rawValue {
                    let resume = self.interrupted && AVAudioSession.InterruptionOptions(rawValue: rawOptions).contains(.shouldResume)
                    self.interrupted = false
                    if resume { self.start() }
                }
            }
        })
        interruptions.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            if note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue {
                Task { @MainActor in self?.pause() }
            }
        })
        #endif
    }

    deinit {
        ticker?.invalidate()
        if let completionObserver { NotificationCenter.default.removeObserver(completionObserver) }
        if let failureObserver { NotificationCenter.default.removeObserver(failureObserver) }
        for (command, token) in remoteTargets { command.removeTarget(token) }
        for token in interruptions { NotificationCenter.default.removeObserver(token) }
    }
}
