import re, sys
xml = open(r'C:\Users\16039\WorkBuddy\记账APP\scripts\ui.xml', encoding='utf-8').read()
for kw in ['全部', '未归类', '新建', '高总']:
    idx = xml.find('text="%s"' % kw)
    if idx < 0:
        print(kw, 'NOT FOUND')
        continue
    seg = xml[idx:idx + 400]
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', seg)
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        print(kw, 'center', (x1 + x2) // 2, (y1 + y2) // 2, 'y-range', y1, y2)
    else:
        print(kw, 'no bounds')
