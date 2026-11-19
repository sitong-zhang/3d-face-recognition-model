#!/usr/bin/env bash
# 下载 Python 端所需的第三方开源模型（原样来自官方，见 THIRD_PARTY_NOTICES.md）
set -e
cd "$(dirname "$0")"
mkdir -p models
cd models
echo "[1/3] dlib 68 点关键点模型 (~96MB)"
curl -fL --retry 3 "https://dlib.net/files/shape_predictor_68_face_landmarks.dat.bz2" -o shape_predictor_68_face_landmarks.dat.bz2
bunzip2 -f shape_predictor_68_face_landmarks.dat.bz2
echo "[2/3] dlib 128D 人脸识别模型 (~22MB)"
curl -fL --retry 3 "https://dlib.net/files/dlib_face_recognition_resnet_model_v1.dat.bz2" -o dlib_face_recognition_resnet_model_v1.dat.bz2
bunzip2 -f dlib_face_recognition_resnet_model_v1.dat.bz2
echo "[3/3] MediaPipe 3D Face Landmarker 模型 (~3.6MB)"
curl -fL --retry 3 "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task" -o face_landmarker.task
echo "完成。模型列表："
ls -lh
