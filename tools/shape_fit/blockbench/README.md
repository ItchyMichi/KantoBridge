# Blockbench review tools (docs/specs/FITTED_SHAPES.md sections 11–13)

On a CPU pod: `setup_bb.sh` (packages, Blockbench 5.2.2 AppImage extracted to /root/bb), `start_bb.sh` (Xvfb :98,
Blockbench with remote debugging on 127.0.0.1:9222, x11vnc + noVNC on 127.0.0.1:6080), a venv with playwright and
pillow. `bbrender.py` renders .bbmodel files (from `../bbmodel.py export`) into sheets with the unmasked sprite;
`bbbatch.py` makes the numbered review sheets, 5 cells each.
