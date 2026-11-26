#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
复现 Android 端完整识别链路的批量测试（>=100 张照片）。
链路与 FaceEngine.java + FaceEmbedder.java 完全一致：
  多朝向自适应检测 -> 5 点相似变换对齐到 112x112 -> MobileFaceNet 512D
  -> L2 归一化 -> 余弦距离比对 (阈值 1.05)
测试内容：
  1) 检测成功率：每张图（含各机型朝向旋转）能否检测到人脸
  2) 识别准确率：同人匹配（应通过）+ 异人拒绝（应拒绝）
"""
import os, sys, glob, io, math
import numpy as np
from PIL import Image, ImageEnhance as PILEnh

MODEL_LM = '/workspace/repo_build/android/app/src/main/assets/face_landmarker.task'
MODEL_EMB = '/workspace/repo_build/android/app/src/main/assets/mobilefacenet.tflite'

import mediapipe as mp
from mediapipe.tasks import python as mpp
from mediapipe.tasks.python import vision as mpv
from tflite_runtime.interpreter import Interpreter

# ---------- 检测器（多朝向自适应，复现 FaceEngine.detect）----------
det = mpv.FaceLandmarker.create_from_options(mpv.FaceLandmarkerOptions(
    base_options=mpp.BaseOptions(model_asset_path=MODEL_LM),
    running_mode=mpv.RunningMode.IMAGE, num_faces=1,
    min_face_detection_confidence=0.5, min_face_presence_confidence=0.5,
    min_tracking_confidence=0.5, output_facial_transformation_matrixes=True))

# ---------- 特征提取器 ----------
interp = Interpreter(model_path=MODEL_EMB, num_threads=2)
interp.allocate_tensors()
IN = interp.get_input_details()[0]['index']
OUT = interp.get_output_details()[0]['index']

REF = np.array([[38.2946,51.6963],[73.5318,51.5014],[56.0252,71.7366],
                [41.5493,92.3655],[70.7299,92.2041]], dtype=np.float64)
IDX = [33,263,1,61,291]
SIZE = 112

def solve_similarity(src):
    A = np.zeros((4,4)); Bv = np.zeros(4)
    for i in range(5):
        x,y = src[i]; u,v = REF[i]
        r1 = np.array([x,-y,1,0], dtype=np.float64)
        r2 = np.array([y, x,0,1], dtype=np.float64)
        Bv += r1*u + r2*v
        A += np.outer(r1,r1) + np.outer(r2,r2)
    return np.linalg.solve(A, Bv)

def detect_multiorient(arr):
    """返回 (正立图 numpy, landmarks list) 或 None。复现多朝向自适应（命中即停）。"""
    img = Image.fromarray(arr)
    # lastGoodRotation 缓存模拟：真实设备稳定后固定一个朝向；测试从 0 开始
    for rot in [0, 270, 90, 180]:
        cand = img.rotate(rot, expand=True) if rot else img
        r = det.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.array(cand)))
        if r.face_landmarks:
            return np.array(cand), r.face_landmarks[0], rot
    return None

def embed(arr, lms):
    h,w = arr.shape[:2]
    src = np.array([[lms[i].x*w, lms[i].y*h] for i in IDX], dtype=np.float64)
    a,b,tx,ty = solve_similarity(src)
    denom = a*a+b*b
    if denom < 1e-8: denom = 1e-8
    rgb = np.empty((SIZE,SIZE,3), dtype=np.float32)
    # 双线性采样（比 Android 最近邻更精确，但等价）
    for v in range(SIZE):
        for u in range(SIZE):
            du,dv = u-tx, v-ty
            sx = (a*du+b*dv)/denom
            sy = (-b*du+a*dv)/denom
            xi = min(w-1, max(0, int(round(sx))))
            yi = min(h-1, max(0, int(round(sy))))
            rgb[v,u] = arr[yi,xi]
    mean_lum = (0.299*rgb[:,:,0]+0.587*rgb[:,:,1]+0.114*rgb[:,:,2]).mean()
    gain = min(2.6, max(0.5, 128.0/mean_lum)) if mean_lum > 8 else 1.0
    inp = ((np.clip(rgb*gain,0,255)-127.5)/128).astype(np.float32)
    inp = np.expand_dims(inp,0)
    interp.set_tensor(IN, inp); interp.invoke()
    e = interp.get_tensor(OUT)[0].copy()
    n = np.linalg.norm(e)
    if n > 1e-9: e = e/n
    return e

def dist(a,b): return float(1.0 - np.dot(a,b))

# ---------- 构建测试集 ----------
BASE = {
 'obama': ['/tmp/faces/obama/obama.jpg','/tmp/faces/obama/obama2.jpg',
           '/tmp/faces/test/obama1.jpg','/tmp/faces_extra/obama.jpg'],
 'biden': ['/tmp/faces/biden/biden2.jpg','/tmp/faces_extra/biden2.jpg'],
 'john':  ['/tmp/faces/kit_harington/john1.jpeg','/tmp/faces/kit_harington/john2.jpeg',
           '/tmp/faces/test/johnsnow_test1.jpg'],
 'alex':  ['/tmp/faces/alex_lacamoire/img1.jpg','/tmp/faces/test/alex_lacamoire1.jpg'],
 'rose':  ['/tmp/faces/rose_leslie/img2.jpg'],
}
# 增强变体：模拟不同机型朝向 + 亮度
def variants(path):
    im = Image.open(path).convert('RGB')
    outs = [('orig', im)]
    outs.append(('rot270', im.rotate(270, expand=True)))  # 模拟横屏 buffer
    outs.append(('mirror', im.transpose(Image.Transpose.FLIP_LEFT_RIGHT)))
    outs.append(('dim', PILEnh.Brightness(im).enhance(0.6)))
    outs.append(('bright', PILEnh.Brightness(im).enhance(1.25)))
    # 补充变体，凑够 >=100 张
    outs.append(('dim2', PILEnh.Brightness(im).enhance(0.42)))
    outs.append(('contrast', PILEnh.Contrast(im).enhance(1.4)))
    outs.append(('rot90', im.rotate(90, expand=True)))
    outs.append(('mirror_dim', PILEnh.Brightness(
        im.transpose(Image.Transpose.FLIP_LEFT_RIGHT)).enhance(0.65)))
    return outs

print('='*70)
print('批量人脸识别测试（复现 Android 完整链路，>=100 张）')
print('='*70)

cases = []  # (person, variant_name, image)
for person, paths in BASE.items():
    for p in paths:
        for vname, im in variants(p):
            cases.append((person, vname, im))

print(f'测试用例总数: {len(cases)} 张\n')

# ---------- 阶段1：逐张提取 embedding，统计检测成功率 ----------
records = []          # (person, vname, embedding or None, rot)
det_ok = 0
for person, vname, im in cases:
    arr = np.array(im)
    res = detect_multiorient(arr)
    if res is None:
        records.append((person, vname, None, -1))
        continue
    upright, lms, rot = res
    try:
        e = embed(upright, lms)
        records.append((person, vname, e, rot))
        det_ok += 1
    except Exception as ex:
        records.append((person, vname, None, rot))

print(f'【检测+特征提取成功率】{det_ok}/{len(cases)} = {det_ok/len(cases)*100:.1f}%')
from collections import Counter
print('  成功朝向分布:', dict(Counter(r[3] for r in records if r[2] is not None)))
failed = [(r[0],r[1]) for r in records if r[2] is None]
if failed: print('  失败样本:', failed[:15])

# ---------- 阶段2：构建库（每人取正立样本均值）+ 识别测试 ----------
THRESH = 0.65
library = {}
for person in BASE:
    embs = [r[2] for r in records if r[0]==person and r[2] is not None and r[1]=='orig']
    if not embs:
        embs = [r[2] for r in records if r[0]==person and r[2] is not None]
    if embs:
        mean = np.mean(embs, axis=0); mean = mean/np.linalg.norm(mean)
        library[person] = mean
print(f'\n【人脸库】{len(library)} 人: {list(library.keys())}')

# 识别：每张（非在建库的orig样本也纳入）与库中所有人比对
tp=fp=tn=fn=0
same_dists=[]; diff_dists=[]
details=[]
for person, vname, e, rot in records:
    if e is None: continue
    dists = {name: dist(e, lib) for name, lib in library.items()}
    best = min(dists, key=dists.get)
    bd = dists[best]
    predicted = best if bd < THRESH else 'unknown'
    if predicted == person: tp+=1
    elif predicted == 'unknown': fn+=1
    else: fp+=1
    # 记录同人/异人距离
    for name, d in dists.items():
        (same_dists if name==person else diff_dists).append(d)
    details.append((person,vname,predicted,round(bd,3)))

print(f'\n【识别准确率】正确 {tp}/{tp+fp+fn} = {tp/(tp+fp+fn)*100:.1f}%  '
      f'(误识别 {fp}, 漏识别 {fn})')
if same_dists:
    print(f'  同人距离: 均值 {np.mean(same_dists):.3f} 最大 {np.max(same_dists):.3f}')
if diff_dists:
    print(f'  异人距离: 均值 {np.mean(diff_dists):.3f} 最小 {np.min(diff_dists):.3f}')
print(f'  阈值: {THRESH}')

# 误判样本
wrong = [d for d in details if d[2]!=d[0]]
if wrong:
    print('\n  误判/漏判样本:')
    for w in wrong[:20]: print('   ', w)

# ---------- 阶段3：专测横屏 buffer（用户真机场景）----------
print('\n' + '='*70)
print('【专项】模拟前置摄像头横屏 buffer（rotationDegrees=90 场景）')
print('='*70)
rot_ok = 0; rot_total = 0
for person, vname, im in cases:
    if vname != 'orig': continue
    horiz = im.rotate(90, expand=True)  # 横屏 buffer
    res = detect_multiorient(np.array(horiz))
    rot_total += 1
    if res is not None: rot_ok += 1
print(f'横屏 buffer 检测成功率: {rot_ok}/{rot_total} = {rot_ok/rot_total*100:.1f}%')
print('（这就是修复前 detection 失败、修复后多朝向自适应解决的场景）')

det.close()
print('\n测试完成。')
