// Optional design tool: node export-background.cjs (requires sharp, not an app dependency).
const sharp = require('sharp');
const path = require('path');

(async () => {
  const source = path.join(__dirname, 'assets/ambient-source-v1.png');
  const destination = path.join(__dirname, '../app/src/main/res/drawable-nodpi');
  const expanded = await sharp(source).resize(2200, 1238, { fit: 'cover' }).toBuffer();
  const crop = { left: 140, top: 79, width: 1920, height: 1080 };
  await sharp(expanded).extract(crop).jpeg({ quality: 94 }).toFile(path.join(destination, 'wallpaper_ambient.jpg'));
  await sharp(expanded).blur(76).extract(crop).jpeg({ quality: 96 }).toFile(path.join(destination, 'wallpaper_ambient_blur.jpg'));
})().catch(error => { console.error(error); process.exitCode = 1; });
