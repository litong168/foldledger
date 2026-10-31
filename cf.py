import struct
def load(path):
    f=open(path,"rb").read(); p=8; cp={}
    def u1():
        nonlocal p; v=f[p]; p+=1; return v
    def u2():
        nonlocal p; v=struct.unpack_from(">H",f,p)[0]; p+=2; return v
    def u4():
        nonlocal p; v=struct.unpack_from(">I",f,p)[0]; p+=4; return v
    n=u2(); i=1
    while i<n:
        t=u1()
        if t==1:
            ln=u2(); cp[i]=f[p:p+ln].decode("utf-8","replace"); p+=ln
        elif t in (7,8,16,19,20): cp[i]=u2()
        elif t==15: cp[i]=(u1(),u2())
        elif t in (3,4): cp[i]=u4()
        elif t in (5,6): p+=8; cp[i]=("<wide>",); i+=1
        elif t in (9,10,11,12,17,18): cp[i]=(u2(),u2())
        else: raise Exception("tag %d"%t)
        i+=1
    return f,p,cp
def methods(path):
    f,p,cp=load(path)
    def r2():
        nonlocal p; v=struct.unpack_from(">H",f,p)[0]; p+=2; return v
    def r4():
        nonlocal p; v=struct.unpack_from(">I",f,p)[0]; p+=4; return v
    r2();r2();ic=r2()
    for _ in range(ic): r2()
    def skip():
        nonlocal p
        c=r2()
        for _ in range(c): r2(); ln=r4(); p+=ln
    fc=r2()
    for _ in range(fc): r2();r2();r2();skip()
    mc=r2(); out=[]
    for _ in range(mc):
        r2(); nm=cp[r2()]; desc=cp[r2()]; na=r2()
        for _ in range(na):
            an=cp[r2()]; ln=r4(); body=f[p:p+ln]; p+=ln
            if an=="Code": out.append((nm,desc,body))
    return cp,out
def code_of(body):
    clen=struct.unpack_from(">I",body,4)[0]; return body[8:8+clen]
def calls(code,cp):
    i=0; out=[]
    while i<len(code):
        op=code[i]
        if op in (0xb6,0xb7,0xb8):
            idx=struct.unpack_from(">H",code,i+1)[0]; e=cp[idx]
            out.append((i,cp[e[1]]+"."+cp[e[2]][1],cp[e[2]][2])); i+=3
        elif op in (0xb9,0xba):
            idx=struct.unpack_from(">H",code,i+1)[0]; e=cp[idx]
            out.append((i,cp[e[1]]+"."+cp[e[2]][1],cp[e[2]][2])); i+=5
        elif 0x99<=op<=0xa8: i+=3
        elif op in (0x10,0x12,0x15,0x16,0x17,0x18,0x19,0x36,0x37,0x38,0x39,0x3a,0xa9,0xbc): i+=2
        else: i+=1
    return out
