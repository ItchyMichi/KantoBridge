# depth_setup.sh: a local venv for entity_depth.py on a fresh CPU pod (the volume is too slow to import from)
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null 2>&1
apt-get install -y -qq --no-install-recommends python3 python3-venv python3-pip >/dev/null 2>&1
[ -x /root/dv/bin/python ] || python3 -m venv /root/dv
/root/dv/bin/pip install -q --index-url https://download.pytorch.org/whl/cpu torch torchvision
/root/dv/bin/pip install -q transformers spandrel scipy pillow numpy
mkdir -p /root/esrgan && cp -n /workspace/kb-fit/esrgan/*.pth /root/esrgan/
/root/dv/bin/python -c "import torch, torchvision, transformers, spandrel; print('DEPTH_SETUP_OK', torch.__version__)"
