import SwiftUI

/// Perspective comes from rotating each jacket around its left edge. Opening one makes
/// room for the record between the selected jacket and the sleeve to its right.
struct RoomSleeves: View {
    let albums:[RoomAlbum]
    let openAlbum:(String)->Void
    @State private var selection = 0
    @State private var opened = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private func index(_ value:Int) -> Int {
        let n = max(1,albums.count)
        return (value % n + n) % n
    }

    private func step(_ amount:Int){
        guard albums.count>1 else{return}
        withAnimation(reduceMotion ? nil:.smooth(duration:0.32)) {
            opened=false;selection=index(selection+amount)
        }
    }

    var body: some View {
        if !albums.isEmpty {
            let active=index(selection)
            let album=albums[active]
            let count=albums.count
            VStack(spacing:15) {
                GeometryReader { g in
                    let side=min(g.size.height*0.78,g.size.width*0.58)
                    let x=g.size.width/2, y=g.size.height*0.51
                    ZStack {
                        ForEach(Array(albums.enumerated()),id:\.element.id) { offset, value in
                            let raw=index(offset-active)
                            let delta=raw > count/2 ? raw-count : raw
                            if abs(delta)<=2 {
                                let isActive=delta==0
                                let spread=side*(opened ? 0.76:0.50)
                                let rotor=isActive && (opened || count==1) ? 0:(delta<0 ? 51:-51)
                                let center=x+CGFloat(delta)*spread-(opened && isActive ? side*0.19:0)
                                ZStack {
                                    if isActive {
                                        Circle().fill(AngularGradient(colors:[.black,Color(white:0.19),.black,Color(white:0.31),.black],center:.center))
                                            .overlay(RoomCover(url:value.artworkURL).clipShape(Circle()).padding(side*0.31))
                                            .overlay(Circle().strokeBorder(.white.opacity(0.2),lineWidth:0.7))
                                            .scaleEffect(0.95).offset(x:opened ? side*0.52:0)
                                    }
                                    RoomCover(url:value.artworkURL,fit:true)
                                        .background(Color(white:0.14))
                                        .overlay(Rectangle().strokeBorder(.white.opacity(0.21),lineWidth:1))
                                        .overlay(alignment:.leading) {Rectangle().fill(.white.opacity(0.23)).frame(width:2)}
                                }
                                .frame(width:side,height:side)
                                .shadow(color:.black.opacity(0.38),radius:7,y:10)
                                .rotation3DEffect(.degrees(Double(rotor)),axis:(x:0,y:1,z:0),perspective:0.52)
                                .scaleEffect(isActive ? 1:0.91)
                                .position(x:center,y:y)
                                .zIndex(isActive ? 5:Double(3-abs(delta)))
                                .onTapGesture {
                                    if isActive {
                                        if opened {openAlbum(value.id)}
                                        else {withAnimation(reduceMotion ? nil:.smooth(duration:0.38)){opened=true}}
                                    } else {step(delta)}
                                }
                                .accessibilityHidden(true)
                            }
                        }
                    }
                    .contentShape(Rectangle()).clipped()
                    .simultaneousGesture(DragGesture(minimumDistance:20).onEnded{ drag in
                        guard abs(drag.translation.width)>abs(drag.translation.height),abs(drag.translation.width)>30 else{return}
                        step(drag.translation.width<0 ? 1:-1)
                    })
                }.frame(height: min(322,max(230,242 + CGFloat(min(count,5))*8)))
                Text(album.title).font(.system(.title3,design:.serif).weight(.semibold)).multilineTextAlignment(.center)
                HStack(spacing:24) {
                    Button {step(-1)} label:{Image(systemName:"chevron.left").frame(width:40,height:40)}
                        .disabled(count<2).accessibilityLabel("上一张专辑")
                    Button {
                        if opened {openAlbum(album.id)}
                        else {withAnimation(reduceMotion ? nil:.smooth(duration:0.35)){opened=true}}
                    } label:{Label(opened ? "查看曲目":"抽出唱片",systemImage:opened ? "music.note.list":"opticaldisc")}
                    .buttonStyle(.bordered)
                    Button {step(1)} label:{Image(systemName:"chevron.right").frame(width:40,height:40)}
                        .disabled(count<2).accessibilityLabel("下一张专辑")
                }.buttonStyle(.plain)
                Text("\(active+1) / \(count)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            .sensoryFeedback(.selection,trigger:selection)
        }
    }
}
