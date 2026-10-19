"""Convert the supplied artwork into application and installer resource formats."""
from pathlib import Path
import sys
from PIL import Image

root = Path(__file__).resolve().parents[1]
out = root / 'composeApp/src/main/resources/icons'
source = Image.open(sys.argv[1]).convert('RGBA')
source.save(out / 'brand-source.png')
for size in (16, 24, 32, 48, 64, 128, 256, 512):
    source.resize((size, size), Image.Resampling.LANCZOS).save(out / f'app_{size}.png')
source.save(out / 'app.ico', sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
source.resize((512, 512), Image.Resampling.LANCZOS).save(out / 'app.icns')
Image.open(sys.argv[2]).convert('RGBA').save(out / 'bilibili.png')
