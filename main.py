import os
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.image import Image
from kivy.uix.filechooser import FileChooserIconView
from kivy.uix.popup import Popup
from kivy.uix.label import Label
from PIL import Image as PILImage, ImageOps, ImageDraw, ImageFont
from PIL.ExifTags import TAGS

class WatermarkApp(App):
    def build(self):
        self.layout = BoxLayout(orientation='vertical', padding=10, spacing=10)
        
        # 1. 图片预览区
        self.img_preview = Image(source='logo.png', size_hint=(1, 0.7)) # 默认显示Logo
        self.layout.add_widget(self.img_preview)

        # 2. 状态标签
        self.label = Label(text="请选择一张照片", size_hint=(1, 0.1))
        self.layout.add_widget(self.label)

        # 3. 按钮区
        btn_layout = BoxLayout(size_hint=(1, 0.2), spacing=10)
        btn_choose = Button(text="选择照片", on_press=self.show_chooser)
        btn_save = Button(text="生成并保存", on_press=self.process_image)
        btn_layout.add_widget(btn_choose)
        btn_layout.add_widget(btn_save)
        self.layout.add_widget(btn_layout)

        self.selected_path = None
        return self.layout

    def show_chooser(self, instance):
        # 弹窗选择文件
        chooser = FileChooserIconView(filters=['*.jpg', '*.jpeg', '*.png'])
        popup_content = BoxLayout(orientation='vertical')
        popup_content.add_widget(chooser)
        
        btn_select = Button(text="确定", size_hint=(1, 0.2))
        popup = Popup(title="选择图片", content=popup_content, size_hint=(0.9, 0.9))

        def on_selection(instance, selection):
            if selection:
                self.selected_path = selection[0]
                self.img_preview.source = self.selected_path
                self.label.text = f"已选中: {os.path.basename(self.selected_path)}"
                popup.dismiss()
        
        chooser.bind(selection=on_selection)
        btn_select.bind(on_press=lambda x: popup.dismiss()) # 简化处理
        popup_content.add_widget(btn_select)
        popup.open()

    def get_exif_data(self, image):
        # 简单提取 Exif
        exif_data = {}
        info = image._getexif()
        if info:
            for tag, value in info.items():
                decoded = TAGS.get(tag, tag)
                exif_data[decoded] = value
        return exif_data

    def process_image(self, instance):
        if not self.selected_path:
            self.label.text = "错误：先选照片！"
            return

        try:
            # --- 核心处理逻辑 (Pillow) ---
            original = PILImage.open(self.selected_path)
            
            # 处理旋转 (手机照片常见问题)
            original = ImageOps.exif_transpose(original)
            
            w, h = original.size
            
            # 计算比例
            border_h = int(w * 0.13) # 边框高度 13%
            new_h = h + border_h
            padding = int(w * 0.04)
            
            # 创建新图
            new_img = PILImage.new('RGB', (w, new_h), (255, 255, 255))
            new_img.paste(original, (0, 0))
            
            # 绘制 Logo
            logo = PILImage.open("logo.png") # 确保你有 logo.png
            logo_h = int(border_h * 0.5)
            logo_w = int(logo.width * (logo_h / logo.height))
            logo = logo.resize((logo_w, logo_h))
            
            logo_x = padding
            logo_y = h + (border_h - logo_h) // 2
            new_img.paste(logo, (logo_x, logo_y), mask=logo if logo.mode=='RGBA' else None)

            # 准备文字
            draw = ImageDraw.Draw(new_img)
            exif = self.get_exif_data(original)
            
            # 获取参数 (如果没有则用默认)
            model = exif.get('Model', 'Leica Camera')
            focal = exif.get('FocalLength', 28.0)
            if hasattr(focal, 'numerator'): focal = float(focal)
            f_number = exif.get('FNumber', 1.7)
            if hasattr(f_number, 'numerator'): f_number = float(f_number)
            iso = exif.get('ISOSpeedRatings', 100)
            
            text_model = str(model)
            text_params = f"{int(focal)}mm f/{f_number} ISO{iso}"

            # 加载字体
            font_size_main = int(border_h * 0.2)
            font_size_sub = int(border_h * 0.16)
            try:
                font = ImageFont.truetype("font.ttf", font_size_main)
                font_sub = ImageFont.truetype("font.ttf", font_size_sub)
            except:
                font = ImageFont.load_default()
                font_sub = ImageFont.load_default()

            # 绘制左侧型号
            text_x = logo_x + logo_w + padding
            text_y = h + (border_h - font_size_main) // 2
            draw.text((text_x, text_y), text_model, font=font, fill=(0,0,0))

            # 绘制右侧参数
            # Pillow 没有自动右对齐，需要算宽度
            param_bbox = draw.textbbox((0, 0), text_params, font=font_sub)
            param_w = param_bbox[2] - param_bbox[0]
            draw.text((w - padding - param_w, text_y), text_params, font=font_sub, fill=(100,100,100))

            # 保存
            save_path = os.path.splitext(self.selected_path)[0] + "_leica.jpg"
            new_img.save(save_path, quality=95)
            self.label.text = f"成功！已保存至: {os.path.basename(save_path)}"
            
            # 更新预览
            self.img_preview.source = save_path
            self.img_preview.reload()

        except Exception as e:
            self.label.text = f"出错: {str(e)}"

if __name__ == '__main__':
    WatermarkApp().run()
