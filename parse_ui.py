import xml.etree.ElementTree as ET

tree = ET.parse('/tmp/ui.xml')
root = tree.getroot()

def walk(node, depth=0):
    txt = node.get('text', '').strip()
    cls = node.get('class', '').split('.')[-1]
    desc = node.get('content-desc', '').strip()
    if txt or desc:
        print('  ' * depth + '[' + cls + '] ' + (txt or desc))
    for child in node:
        walk(child, depth + 1)

walk(root)
