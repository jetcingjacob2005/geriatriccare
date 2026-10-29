from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import textwrap

OUT = Path('tmp_topic_flowcharts')
OUT.mkdir(exist_ok=True)

flows = {
    '1': ['Start with real\ndistribution P', 'Choose model\ndistribution Q', 'Calculate\nP log(P/Q)', 'Check direction:\nP||Q or Q||P', 'Observe missing\nor extra regions', 'Conclude mode\ncovering or seeking'],
    '2': ['List outcomes and\nprobabilities P(x)', 'Find surprise\n-log P(x)', 'Weight surprise\nby P(x)', 'Sum to get\nentropy H(P)', 'Use Q in log for\ncross-entropy', 'Subtract H(P)\nto get KL'],
    '3': ['Input sample x\nfrom data space', 'Encoder hidden\nfeatures', 'Output mu and\nlog sigma squared', 'Convert to sigma\nand sample z', 'z is placed in\nlatent space', 'Decoder maps z\nback to x-hat'],
    '4': ['Input x enters\nencoder', 'Sample z from\nq(z|x)', 'Decoder defines\np(x|z)', 'Compute MSE\nor BCE', 'Compute KL to\nprior N(0,I)', 'Add both for\nVAE loss'],
    '5': ['Encoder outputs\nmu and sigma', 'Draw epsilon from\nstandard normal', 'Keep epsilon\nindependent', 'Build z = mu +\nsigma epsilon', 'Differentiate z\nw.r.t. mu/sigma', 'Update encoder\nusing gradients'],
    '6': ['Multiply x by\nencoder weights', 'Apply ReLU to\nhidden values', 'Calculate mu and\nlog variance', 'Sample z using\nepsilon', 'Decode z to\nreconstruction x-hat', 'Compute MSE/BCE\nplus KL'],
    '7': ['VAE uses\nreconstruction loss', 'MSE may average\nvalid outputs', 'Gaussian and\nlikelihood limits', 'KL can cause\nposterior collapse', 'Need realistic\nfine details', 'Use GAN\ncompetition'],
    '8': ['Sample noise z\nfrom prior', 'Generator makes\nfake G(z)', 'Discriminator\noutputs real score', 'Generator step:\nfreeze D, target 1', 'Discriminator step:\nfreeze G, labels 1/0', 'Repeat alternating\nupdates'],
    '9': ['Draw real x from\np_data', 'Draw z and make\nfake G(z)', 'D maximizes real\nand fake terms', 'Write value\nfunction V(D,G)', 'G minimizes fake\nterm to fool D', 'Combine as\nmin G max D'],
    '10': ['Initially p_g differs\nfrom p_data', 'D easily separates\nreal and fake', 'G receives\nlearning signal', 'p_g moves closer\nto p_data', 'At match D\noutputs 0.5', 'Neither improves:\nNash equilibrium'],
}

font_file = r'C:\Windows\Fonts\arial.ttf'
bold_font_file = r'C:\Windows\Fonts\arialbd.ttf'
title_font = ImageFont.truetype(bold_font_file, 42)
body_font = ImageFont.truetype(bold_font_file, 27)

for key, steps in flows.items():
    image = Image.new('RGB', (2000, 720), 'white')
    draw = ImageDraw.Draw(image)
    title = f'Topic {key}  Full Process Flowchart'
    title_box = draw.textbbox((0, 0), title, font=title_font)
    draw.text(((2000 - (title_box[2] - title_box[0])) // 2, 22), title, fill='#000000', font=title_font)
    for i, step in enumerate(steps):
        row = i // 3
        col = i % 3 if row == 0 else 2 - (i % 3)
        x = 105 + col * 640
        y = 135 + row * 330
        draw.rounded_rectangle((x, y, x + 390, y + 180), radius=25, fill='#EAF2F8', outline='#1F4E78', width=5)
        wrapped = '\n'.join(textwrap.wrap(step.replace('\n', ' '), width=18))
        lines = wrapped.splitlines()
        line_height = 34
        start_y = y + 90 - (len(lines) * line_height) // 2
        for j, line in enumerate(lines):
            box = draw.textbbox((0, 0), line, font=body_font)
            draw.text((x + 195 - (box[2] - box[0]) // 2, start_y + j * line_height), line, fill='#111111', font=body_font)
        if i < len(steps) - 1:
            if i == 2:
                draw.line((x + 195, y + 185, x + 195, y + 300), fill='#4F81BD', width=8)
                draw.polygon([(x + 195, y + 300), (x + 179, y + 276), (x + 211, y + 276)], fill='#4F81BD')
            else:
                direction = 1 if row == 0 else -1
                start = x + (400 if direction == 1 else 0)
                end = start + direction * 205
                mid = y + 90
                draw.line((start, mid, end, mid), fill='#4F81BD', width=8)
                arrow = [(end, mid), (end - direction * 24, mid - 16), (end - direction * 24, mid + 16)]
                draw.polygon(arrow, fill='#4F81BD')
    image.save(OUT / f'topic_{key}_flowchart.png')
print(f'Created {len(flows)} flowcharts in {OUT.resolve()}')
