import UIKit
import NouriShared

/// The only copy of a photo that ever leaves the phone: at most 1536 px on the long
/// edge and re-encoded as JPEG, so location and camera metadata are gone.
enum ImageProcessing {
    struct Processed {
        let bytes: KotlinByteArray
        let width: Int32
        let height: Int32
    }

    static func process(_ image: UIImage, maxEdge: CGFloat = 1536) -> Processed? {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return nil }
        let scale = min(1, maxEdge / max(size.width, size.height))
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let resized = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        guard let jpeg = resized.jpegData(compressionQuality: 0.82) else { return nil }
        return Processed(bytes: IosPlatform.shared.bytes(data: jpeg), width: Int32(target.width), height: Int32(target.height))
    }
}
