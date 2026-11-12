import SwiftUI

struct RecordDeck: View {
    let cover: URL?
    let palette: RoomPalette
    let playing: Bool
    let fraction: Double
    let motion: Bool
    var openAlbum: () -> Void
    var toggle: () -> Void
    var seek: (Double) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var draggedAngle: Double?
    private static let arm = RoomImages.texture("tonearm-graphite-v1")
    private static let disc = RoomImages.texture("disc-smoke-lilac-v1")

    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height
            let r = min(w * 0.433, h * 0.345)
            let cx = w * 0.465, cy = h - r - 16
            let top = cy-r
            let sleeve = max(8, min(w*0.42,(top-6)/1.15))
            let left = max(w*0.055,sleeve * sin(.pi*8/180) + max(6,w*0.018)), slotY = top + r*0.052
            let pivotX = cx+r*0.994, pivotY = cy-r*0.748
            let armHeight = r*1.908, armWidth = armHeight/3
            let energy = motion && !reduceMotion
            ZStack(alignment: .topLeading) {
                Path { path in
                    path.move(to: CGPoint(x: -8, y: cy-r*0.23))
                    path.addQuadCurve(to: CGPoint(x: w+6, y: top-r*0.10),
                                      control: CGPoint(x: w*0.22, y: top-r*0.02))
                    path.addLine(to: CGPoint(x: w+6,y: 0))
                    path.addLine(to: .zero); path.closeSubpath()
                }
                .fill(LinearGradient(colors: [palette.surface.opacity(0.22), .black.opacity(0.06), .white.opacity(0.08)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
                .shadow(color:.black.opacity(0.42),radius:2,y:3)
                Capsule().fill(Color.black.opacity(0.85))
                    .frame(width: sleeve*1.13,height:max(4,sleeve*0.065))
                    .rotationEffect(.degrees(-8),anchor:.leading)
                    .position(x:left+sleeve*0.51,y:slotY)
                Button(action: openAlbum) {
                    RoomCover(url: cover, fit: true)
                        .frame(width:sleeve,height:sleeve)
                        .background(palette.surface)
                        .overlay(Rectangle().strokeBorder(.white.opacity(0.28),lineWidth:1))
                        .shadow(color:.black.opacity(0.40),radius:2,x:2,y:2)
                }
                .buttonStyle(.plain).rotationEffect(.degrees(-8),anchor:.bottomLeading)
                .position(x:left+sleeve/2,y:slotY-sleeve/2)
                .accessibilityLabel("查看当前专辑")

                Ellipse().fill(palette.light.opacity(0.52))
                    .frame(width:r*1.26,height:r*0.76)
                    .blur(radius:r*0.15).position(x:cx-r*0.38,y:cy+r*0.54)
                    .accessibilityHidden(true)
                vinyl(diameter:r*2, animated:playing && energy)
                    .position(x:cx,y:cy).accessibilityHidden(true)
                if let arm = Self.arm {
                    Image(decorative:arm,scale:1)
                        .resizable().interpolation(.high).aspectRatio(contentMode:.fit)
                        .frame(width:armWidth,height:armHeight)
                        .shadow(color:.black.opacity(0.4),radius:2,x:2,y:3)
                        .rotationEffect(.degrees(draggedAngle ?? (playing ? 16+min(max(fraction,0),1)*13 : 0)),anchor:UnitPoint(x:0.5,y:0.1855))
                        .animation(energy && draggedAngle == nil ? .easeInOut(duration:0.50) : nil,value:playing)
                        .position(x:pivotX,y:pivotY+armHeight*(0.5-0.1855))
                        .accessibilityHidden(true)
                    // Accept a drag only on the real arm, leaving the cover button available above it.
                    Rectangle().fill(.clear)
                        .frame(width:r*0.97,height:armHeight*0.85)
                        .contentShape(Rectangle())
                        .position(x:pivotX-r*0.35,y:pivotY+armHeight*0.40)
                        .gesture(DragGesture(minimumDistance:5,coordinateSpace:.named("deck")).onChanged { d in
                            guard d.startLocation.y >= pivotY else { return }
                            let resting = playing ? 16 + min(1,max(0,fraction))*13 : 0
                            let initial = atan2(pivotX-d.startLocation.x,d.startLocation.y-pivotY)*180/Double.pi
                            guard draggedAngle != nil || abs(initial-resting)<12 else {return}
                            draggedAngle = min(31,max(0,atan2(pivotX-d.location.x,d.location.y-pivotY)*180/Double.pi))
                        }.onEnded { _ in
                            guard let a = draggedAngle else {return}; draggedAngle=nil
                            if a < 8 { if playing { toggle() } }
                            else {
                                if !playing { toggle() }
                                seek(min(1,max(0,(a-16)/13)))
                            }
                        }).accessibilityHidden(true)
                }
            }
            .coordinateSpace(name:"deck")
            .frame(width:w,height:h)
        }
    }

    private func vinyl(diameter d: CGFloat, animated: Bool) -> some View {
        ZStack {
            Circle().fill(palette.tint)
            if let material = Self.disc {
                Image(decorative:material,scale:1).resizable().scaledToFill().scaleEffect(1.05)
                    .saturation(0).contrast(1.18).opacity(0.62).blendMode(.softLight)
            }
            Canvas { context, size in
                let pitch = max(1.35,size.width / 190)
                for inset in stride(from: CGFloat(3),to:size.width*0.305,by:pitch) {
                    let box = CGRect(origin:.zero,size:size).insetBy(dx:inset,dy:inset)
                    context.stroke(Path(ellipseIn:box),with:.color(.black.opacity(0.20)),lineWidth:0.50)
                }
            }
            Circle().fill(AngularGradient(colors:[.white.opacity(0.11),.clear,.black.opacity(0.12),.white.opacity(0.14),.clear,.black.opacity(0.16),.white.opacity(0.11)],center:.center))
            RotatingLabel(url:cover, spinning:animated)
                .frame(width:d*0.42,height:d*0.42)
            Circle().fill(LinearGradient(colors:[Color(white:0.9),Color(white:0.54),Color(white:0.16)],startPoint:.topLeading,endPoint:.bottomTrailing))
                .frame(width:d*0.039,height:d*0.039).shadow(radius:1,y:1)
        }
        .frame(width:d,height:d).compositingGroup().clipShape(Circle())
        .overlay(Circle().strokeBorder(LinearGradient(colors:[.white.opacity(0.18),.black.opacity(0.24),palette.light.opacity(0.9)],startPoint:.topTrailing,endPoint:.bottomLeading),lineWidth:1.3))
        .shadow(color:.black.opacity(0.4),radius:5,y:7)
    }
}

private struct RotatingLabel: View {
    let url: URL?
    let spinning: Bool
    @State private var stored: Double = 0
    @State private var started: Date?
    @Environment(\.scenePhase) private var phase

    private var moving: Bool { spinning && phase == .active }
    private func angle(at t: Date) -> Double {
        (stored+(started.map { max(0,t.timeIntervalSince($0))*50 } ?? 0)).truncatingRemainder(dividingBy:360)
    }
    var body: some View {
        TimelineView(.animation(minimumInterval:1.0/30, paused:!moving)) { clock in
            RoomCover(url:url).clipShape(Circle()).rotationEffect(.degrees(angle(at:clock.date)))
                .overlay(Circle().strokeBorder(.black.opacity(0.20),lineWidth:1))
        }
        .onChange(of: moving, initial:true) { _, running in
            stored=angle(at:Date()); started=running ? Date() : nil
        }
    }
}
