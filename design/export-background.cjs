// Optional design tool: node export-background.cjs (requires sharp, not an app dependency).
const sharp = require('sharp');
const path = require('path');

(async () => {
  const source = path.join(__dirname, 'assets/ambient-source-v4.png');
  const destination = path.join(__dirname, '../app/src/main/res/drawable-nodpi');
  const image = await sharp(source).resize(1920, 1080, { fit: 'cover' }).toBuffer();
  await sharp(image).jpeg({ quality: 94 }).toFile(path.join(destination, 'wallpaper_ambient.jpg'));
  // Pad before blur so the full composition is retained without dark edge artifacts.
  const padded = await sharp(image).extend({ top: 144, bottom: 144, left: 144, right: 144, extendWith: 'copy' }).toBuffer();
  await sharp(padded).blur(48).extract({ left: 144, top: 144, width: 1920, height: 1080 })
    .jpeg({ quality: 96 }).toFile(path.join(destination, 'wallpaper_ambient_blur.jpg'));
})().catch(error => { console.error(error); process.exitCode = 1; });
