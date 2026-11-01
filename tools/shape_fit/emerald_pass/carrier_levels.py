"""Exact relative source levels and independent part bounds for stepped native carriers."""
from __future__ import annotations
import math


def valid(witness, actual, parts):
    d=len(actual);w=len(actual[0]);values=[v for row in actual for v in row if v is not None]
    if witness is None:return len(set(values))==1
    if (not isinstance(witness,list) or len(witness)!=d or any(not isinstance(row,list) or len(row)!=w for row in witness)
            or not isinstance(parts,list) or not parts):return False
    low=min(values);uniform=len(set(values))==1;zero=False
    for z,row in enumerate(actual):
        for x,level in enumerate(row):
            v=witness[z][x]
            if level is None:
                if v is not None:return False
            elif type(v) is not int or not 0<=v<=30 or not uniform and level-low!=v:return False
            else:zero|=v==0
    if not zero:return False
    for part in parts:
        try:
            if 'carve' in part:return False
            shape=part['shape']
            if shape=='box':
                if len(part['x'])!=2 or len(part['z'])!=2:return False
                x0,x1=part['x'];z0,z1=part['z']
            elif shape in ('cone','cylinder','dome'):
                cx,cz=part['center'];radius=part['radius'];rx,rz=radius if isinstance(radius,list) else (radius,radius)
                if rx<=0 or rz<=0:return False
                x0,x1,z0,z1=cx-rx,cx+rx,cz-rz,cz+rz
            else:return False
            if not all(math.isfinite(v) for v in (x0,x1,z0,z1)) or not(x0<x1 and z0<z1):return False
        except (KeyError,ValueError,TypeError):return False
        for z in range(d):
            for x in range(w):
                if witness[z][x] is None:continue
                if x+1<w and witness[z][x+1] is not None and witness[z][x]!=witness[z][x+1] and z0<16*(z+1) and z1>16*z and x0<16*(x+1)+1 and x1>16*(x+1)-1:return False
                if z+1<d and witness[z+1][x] is not None and witness[z][x]!=witness[z+1][x] and x0<16*(x+1) and x1>16*x and z0<16*(z+1)+1 and z1>16*(z+1)-1:return False
    return True
