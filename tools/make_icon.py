"""Make the launcher icon files from icon/logo.png (440x440 with a gold frame).
Writes icon/fg.png (adaptive icon foreground, 432x432 = 108dp at 4x) and icon/legacy.png (192x192).
Needs Pillow. The outputs are committed, so a normal build does not run this."""
from PIL import Image, ImageDraw
im = Image.open('icon/logo.png').convert('RGBA')
c = im.crop((26, 26, 414, 414))            # inside the gold frame
m = Image.new('L', c.size, 0)
ImageDraw.Draw(m).rounded_rectangle((0, 0, c.size[0] - 1, c.size[1] - 1), radius=40, fill=255)
c.putalpha(m)                               # drop the frame's inner corners
S, up = 232, 9                              # fits the 66dp safe circle; lifted a little
c = c.resize((S, S), Image.LANCZOS)
fg = Image.new('RGBA', (432, 432), (0, 0, 0, 0))
fg.paste(c, ((432 - S) // 2, (432 - S) // 2 - up), c)
fg.save('icon/fg.png', optimize=True)
im.resize((192, 192), Image.LANCZOS).save('icon/legacy.png', optimize=True)
