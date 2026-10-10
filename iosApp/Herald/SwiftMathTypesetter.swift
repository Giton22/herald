import HeraldShared
import SwiftMath
import UIKit

/// TeX math in the chat, typeset by SwiftMath (an iosMath port with its own Latin Modern math font), as
/// JLaTeXMath does on Android. The shared code asks once per formula, size and colour and keeps the picture.
final class SwiftMathTypesetter: NSObject, NativeMathTypesetter {
    func typeset(latex: String, textSizePx: Float, argb: Int32, display: Bool) -> Data? {
        // The picture comes back at the screen's scale, so points times scale is the pixel size asked for.
        let scale = UIScreen.main.scale
        let value = UInt32(bitPattern: argb)
        let color = UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: CGFloat((value >> 24) & 0xFF) / 255
        )
        let fontSize = CGFloat(textSizePx) / scale
        let image = MTMathImage(
            latex: latex,
            fontSize: fontSize,
            textColor: color,
            labelMode: display ? .display : .text,
            textAlignment: .left
        )
        // SwiftMath sizes the picture to the glyph boxes, which tall delimiters and italic letters reach past:
        // without a margin a matrix lost the bottom of its brackets. Even top and bottom keep it centred on the
        // line, as the chat places it.
        let margin = (fontSize * 0.15).rounded(.up)
        image.contentInsets = MTEdgeInsets(top: margin, left: 1, bottom: margin, right: (fontSize * 0.08).rounded(.up))
        let (error, rendered) = image.asImage()
        guard error == nil, let rendered else { return nil }
        return rendered.pngData()
    }
}
