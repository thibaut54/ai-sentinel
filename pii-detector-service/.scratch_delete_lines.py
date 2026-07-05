import sys

def delete_ranges(path, ranges):
    """ranges: list of (start,end) 1-indexed inclusive, will be deleted in descending order."""
    with open(path, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    for start, end in sorted(ranges, key=lambda r: -r[0]):
        del lines[start-1:end]
    with open(path, 'w', encoding='utf-8') as f:
        f.writelines(lines)

if __name__ == '__main__':
    path = sys.argv[1]
    ranges = []
    for pair in sys.argv[2:]:
        s, e = pair.split(':')
        ranges.append((int(s), int(e)))
    delete_ranges(path, ranges)
    print(f"Deleted {len(ranges)} range(s) from {path}")
