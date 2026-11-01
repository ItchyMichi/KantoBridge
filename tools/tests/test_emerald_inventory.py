import numpy as np
from PIL import Image

from tools.shape_fit.blue_pass.common import Model
from tools.shape_fit.emerald_pass.inventory import orthographic


def test_orthographic_front_east_and_top_use_outermost_occupied_texel():
    model=Model()
    model.box((2,3),(3,4),(4,5),1)
    model.box((2,3),(3,4),(8,9),2)
    model.box((9,10),(3,4),(8,9),3)
    model.box((9,10),(7,8),(8,9),4)
    sprite=Image.new('RGB',(16,16))
    for n in range(1,5):sprite.putpixel((n,0),(n*40,0,0))
    front,east,top=orthographic(model.entry({}),sprite)
    assert front.getpixel((2,12))==(80,0,0,255)
    assert east.getpixel((8,12))==(120,0,0,255)
    assert top.getpixel((9,8))==(160,0,0,255)
    assert front.getpixel((0,0))[3]==0
