// Значок iPhone 1024×1024 из docs/mockups/icon/icon-master.svg (шаг 5.1): те же фигуры в CoreGraphics.
// Запуск: swift iosApp/tools/icon.swift iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let size = 1024
let k = CGFloat(size) / 72
func color(_ hex: UInt32) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
}
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
// Ось Y в SVG — вниз.
ctx.translateBy(x: 0, y: CGFloat(size))
ctx.scaleBy(x: k, y: -k)
ctx.setFillColor(color(0xF3EFE6))
ctx.fill(CGRect(x: 0, y: 0, width: 72, height: 72))
ctx.setStrokeColor(color(0x15171A))
ctx.setLineWidth(8)
ctx.setLineJoin(.miter)
ctx.setLineCap(.butt)
ctx.move(to: CGPoint(x: 23, y: 53))
ctx.addLine(to: CGPoint(x: 23, y: 21))
ctx.addLine(to: CGPoint(x: 49, y: 21))
ctx.addLine(to: CGPoint(x: 49, y: 53))
ctx.strokePath()
ctx.setFillColor(color(0xFF5A1F))
ctx.fillEllipse(in: CGRect(x: 31, y: 42, width: 10, height: 10))
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
CGImageDestinationFinalize(dest)
