import SwiftUI
import ImageIO
import CoreGraphics

private let covers = NSCache<NSURL, CGImage>()

enum RoomImages {
    static func cover(_ url: URL, edge: Int = 640) async -> CGImage? {
        let key = url as NSURL
        if let hit = covers.object(forKey: key) { return hit }
        let remote: Data?
        if url.isFileURL { remote = nil }
        else {
            guard url.scheme?.lowercased() == "https" else {return nil}
            do {
                var request=URLRequest(url:url,cachePolicy:.returnCacheDataElseLoad,timeoutInterval:12)
                request.setValue("image/*",forHTTPHeaderField:"Accept")
                let (file,response) = try await URLSession.shared.download(for:request)
                guard (response as? HTTPURLResponse)?.statusCode == 200,
                      let size=try file.resourceValues(forKeys:[.fileSizeKey]).fileSize,
                      size<=8*1024*1024 else {return nil}
                remote=try Data(contentsOf:file,options:.mappedIfSafe)
            } catch {return nil}
        }
        return await Task.detached(priority: .userInitiated) {
            let source:CGImageSource?
            if let remote { source=CGImageSourceCreateWithData(remote as CFData,nil) }
            else { source=CGImageSourceCreateWithURL(key,nil) }
            guard let source,
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: edge
                  ] as CFDictionary) else { return nil }
            covers.totalCostLimit = 24 * 1024 * 1024
            covers.setObject(image, forKey: key, cost: image.bytesPerRow * image.height)
            return image
        }.value
    }

    static func texture(_ name: String) -> CGImage? {
        guard let url = Bundle.main.url(forResource: name, withExtension: "png"),
              let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }
}

struct RoomCover: View {
    let url: URL?
    var fit = false
    @State private var image: CGImage?
    var body: some View {
        GeometryReader { g in
            if let image {
                Image(decorative: image, scale: 1)
                    .resizable().aspectRatio(contentMode: fit ? .fit : .fill)
                    .frame(width: g.size.width, height: g.size.height).clipped()
            } else {
                ZStack {
                    Color(white: 0.18)
                    Circle().fill(Color(white: 0.12)).padding(g.size.width * 0.12)
                    Image(systemName: "music.note").font(.system(size: max(12, min(g.size.width, g.size.height) * 0.19)))
                        .foregroundStyle(.white.opacity(0.65))
                }
            }
        }
        .accessibilityHidden(true)
        .task(id: url) {
            image = nil
            if let url {
                let loaded = await RoomImages.cover(url)
                if !Task.isCancelled { image = loaded }
            }
        }
    }
}

struct RoomPalette {
    let stage: Color
    let surface: Color
    let tint: Color
    let light: Color

    static let initial = RoomPalette(hue: 0.075, saturation: 0.60, value: 0.78)

    private init(hue: Double, saturation: Double, value: Double) {
        stage = Color(hue: hue, saturation: saturation * 0.32, brightness: 0.12 + value * 0.085)
        surface = Color(hue: hue, saturation: saturation * 0.42, brightness: 0.17 + value * 0.095)
        tint = Color(hue: hue, saturation: saturation * 0.84, brightness: 0.39 + value * 0.31)
        light = Color(hue: hue, saturation: saturation * 0.70, brightness: 0.78 + value * 0.21)
    }

    static func extract(_ image: CGImage) -> RoomPalette {
        let size = 36
        var bytes = [UInt8](repeating: 0, count: size*size*4)
        let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let rgb = CGColorSpace(name: CGColorSpace.sRGB),
                  let c = CGContext(data: buffer.baseAddress, width: size, height: size, bitsPerComponent: 8,
                                    bytesPerRow: size*4, space: rgb, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            c.draw(image, in: CGRect(x: 0,y: 0,width: size,height: size))
            return true
        }
        guard drawn else { return .initial }
        struct Bucket { var weight = 0.0, r = 0.0, g = 0.0, b = 0.0 }
        var buckets = [Bucket](repeating: Bucket(), count: 24)
        for i in stride(from: 0, to: bytes.count, by: 4) where bytes[i+3] > 160 {
            let r = Double(bytes[i])/255, g = Double(bytes[i+1])/255, b = Double(bytes[i+2])/255
            let h = hsv(r,g,b), weight = (0.18 + h.1 * 1.3) * (0.08 + h.2*h.2)
            let k = h.1 < 0.09 ? 23 : min(22,Int(h.0 * 23))
            buckets[k].weight += weight
            buckets[k].r += r*weight; buckets[k].g += g*weight; buckets[k].b += b*weight
        }
        guard let best = buckets.max(by: { $0.weight < $1.weight }), best.weight > 0 else { return .initial }
        let h = hsv(best.r/best.weight, best.g/best.weight, best.b/best.weight)
        return RoomPalette(hue: h.0, saturation: h.1, value: h.2)
    }

    private static func hsv(_ r: Double,_ g: Double,_ b: Double) -> (Double,Double,Double) {
        let v = max(r,g,b), c = v-min(r,g,b)
        guard c > 0.001 else { return (0,0,v) }
        var h: Double
        if v == r { h = (g-b)/c } else if v == g { h = 2+(b-r)/c } else { h = 4+(r-g)/c }
        h /= 6
        return (h < 0 ? h+1:h, c/v,v)
    }
}
