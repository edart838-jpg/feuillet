#!/bin/bash
# Télécharge les modèles PP-OCRv6 « tiny » (détection et reconnaissance, format ONNX) publiés par PaddlePaddle
# sur Hugging Face, et extrait l'alphabet du reconnaisseur. Les fichiers vont dans les ressources de l'application.
set -euo pipefail
D=app/src/main/assets/ppocr
H=https://huggingface.co/PaddlePaddle
mkdir -p "$D"
curl -fsSL --retry 3 -o "$D/det.onnx" "$H/PP-OCRv6_tiny_det_onnx/resolve/main/inference.onnx"
curl -fsSL --retry 3 -o "$D/rec.onnx" "$H/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.onnx"
curl -fsSL --retry 3 -o /tmp/ppocr_rec.yml "$H/PP-OCRv6_tiny_rec_onnx/resolve/main/inference.yml"
curl -fsSL --retry 3 -o /tmp/ppocr_det.yml "$H/PP-OCRv6_tiny_det_onnx/resolve/main/inference.yml" || true
python3 -c "import yaml" 2>/dev/null || pip install -q pyyaml
python3 - "$D/dict.txt" <<'PY'
import sys, yaml
cfg = yaml.safe_load(open('/tmp/ppocr_rec.yml', encoding='utf-8'))
def find(node, key):
    if isinstance(node, dict):
        if key in node: return node[key]
        for v in node.values():
            r = find(v, key)
            if r is not None: return r
    elif isinstance(node, list):
        for v in node:
            r = find(v, key)
            if r is not None: return r
    return None
chars = find(cfg, 'character_dict')
if not isinstance(chars, list) or len(chars) < 50:
    print('alphabet introuvable ; clés :', list(cfg.keys()) if isinstance(cfg, dict) else type(cfg)); sys.exit(1)
open(sys.argv[1], 'w', encoding='utf-8').write('\n'.join(str(c) for c in chars) + '\n')
print('alphabet :', len(chars), 'caractères ; exemples :', ''.join(str(c) for c in chars[:40]))
print('prétraitement du reconnaisseur :', find(cfg, 'PreProcess'))
try:
    det = yaml.safe_load(open('/tmp/ppocr_det.yml', encoding='utf-8'))
    print('prétraitement du détecteur :', find(det, 'PreProcess')); print('post-traitement du détecteur :', find(det, 'PostProcess'))
except Exception as e:
    print('configuration du détecteur non lue :', e)
PY
ls -la "$D"
