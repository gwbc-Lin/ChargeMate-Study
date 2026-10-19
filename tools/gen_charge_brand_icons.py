"""Raster fallback for OEM notification/application icon loaders; matches vector brand."""
from pathlib import Path
from PIL import Image,ImageDraw
R=Path(__file__).resolve().parents[1]/'app/src/main/res'
S=864
im=Image.new('RGBA',(S,S));pix=im.load()
for y in range(S):
 for x in range(S):
  t=min(1,max(0,(x+y)/(2*(S-1))))
  pix[x,y]=tuple(round(a+(b-a)*t) for a,b in zip((64,48,95),(21,21,39)))+(255,)
brand=Image.new('RGBA',(S,S))
d=ImageDraw.Draw(brand);scale=S/108
pt=lambda points:[(x*scale,y*scale) for x,y in points]
# Ring: endpoint top-right, sweep the long way via the left side to bottom-right.
d.arc(tuple(v*scale for v in (34,34,74,74)),start=42,end=318,fill='#C8B9FF',width=round(5*scale))
import math
for angle in (42,318):
 x=54+20*math.cos(math.radians(angle));y=54+20*math.sin(math.radians(angle));rad=2.5
 d.ellipse(tuple(v*scale for v in (x-rad,y-rad,x+rad,y+rad)),fill='#C8B9FF')
d.polygon(pt([(57.5,37),(44.5,56),(53,56),(49.5,71),(64,50.5),(55.7,50.5)]),fill='#FFF8FF')
d.polygon(pt([(75,27),(77.1,32.9),(83,35),(77.1,37.1),(75,43),(72.9,37.1),(67,35),(72.9,32.9)]),fill='#F2B9D8')
# Enlarge only the mark, keeping the background and outer icon dimensions fixed.
# Center the combined ring/bolt/star rather than the ring alone.
brand=brand.transform((S,S),Image.Transform.AFFINE,
 (1/1.5,0,58*S/108-S/3,0,1/1.5,51*S/108-S/3),resample=Image.Resampling.BICUBIC)
im.alpha_composite(brand)
for density,size in {'mdpi':48,'hdpi':72,'xhdpi':96,'xxhdpi':144,'xxxhdpi':192}.items():
 folder=R/('mipmap-'+density);folder.mkdir(exist_ok=True)
 icon=im.resize((size,size),Image.Resampling.LANCZOS)
 icon.save(folder/'ic_charge_launcher.png')
 icon.save(folder/'ic_wirelesspet_brand.png')
 mask=Image.new('L',(S,S));ImageDraw.Draw(mask).ellipse((0,0,S-1,S-1),fill=255)
 rounded=im.copy();rounded.putalpha(mask);rounded.resize((size,size),Image.Resampling.LANCZOS).save(folder/'ic_charge_launcher_round.png')
 rounded.resize((size,size),Image.Resampling.LANCZOS).save(folder/'ic_wirelesspet_brand_round.png')
 # Small icon has only white foreground and transparent negative space.
 small=Image.new('RGBA',(192,192));sd=ImageDraw.Draw(small);k=8
 sd.arc(tuple(v*k for v in (4,4,20,20)),start=16,end=320,fill='white',width=16)
 sd.polygon([(x*k,y*k) for x,y in [(13.5,5),(8,13),(11.5,13),(10,19),(16.5,10),(12.7,10)]],fill='white')
 nf=R/('drawable-'+density);nf.mkdir(exist_ok=True)
 small.resize((round(24*size/48),round(24*size/48)),Image.Resampling.LANCZOS).save(nf/'ic_notification_charge.png')
print('Raster fallback generated: launcher and notification, 5 densities')
