from pathlib import Path
from pypdf import PdfReader

source_dir = Path(r"C:\Users\ASUS TUF\Downloads")
output_dir = Path("tmp_lecture_text")
output_dir.mkdir(exist_ok=True)

for lecture in [19, 20, 21, 22, 23, 24, 31, 32, 33, 34]:
    source = source_dir / f"Lec {lecture}.pdf"
    reader = PdfReader(str(source))
    pages = []
    for index, page in enumerate(reader.pages, start=1):
        pages.append(f"\n\n===== PAGE {index} =====\n\n{page.extract_text() or ''}")
    (output_dir / f"Lec {lecture}.txt").write_text("".join(pages), encoding="utf-8")
    print(f"Lec {lecture}: {len(reader.pages)} pages")
