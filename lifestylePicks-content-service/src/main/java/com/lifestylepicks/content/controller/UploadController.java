package com.lifestylepicks.content.controller;
import com.lifestylepicks.common.context.UserContext;
import com.lifestylepicks.common.dto.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.io.InputStream;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/upload")
public class UploadController {
    private final Path root;
    public UploadController(@Value("${lifestylepicks.content.upload-dir:./uploads}") String directory) {
        root=Paths.get(directory).toAbsolutePath().normalize();
    }
    @PostMapping("/blog")
    public Result upload(@RequestParam("file") MultipartFile image) throws Exception {
        String original=image.getOriginalFilename();
        String extension=original!=null && original.contains(".") ? original.substring(original.lastIndexOf('.')+1).toLowerCase(Locale.ROOT) : "";
        if (!Arrays.asList("png","jpg","jpeg","gif","bmp").contains(extension) || image.isEmpty()) { return Result.fail("请上传支持的图片文件"); }
        try(InputStream input=image.getInputStream()) { if(ImageIO.read(input)==null) { return Result.fail("图片内容无效"); } }
        catch(IOException invalid){return Result.fail("图片内容无效");}
        String name="blogs/"+UserContext.getUserId()+"/"+UUID.randomUUID()+"."+extension;
        Path file=root.resolve(name).normalize(); Files.createDirectories(file.getParent()); image.transferTo(file.toFile());
        return Result.ok("/"+name);
    }
    @GetMapping("/blog/delete")
    public Result delete(@RequestParam String name) throws Exception {
        String relative=name.startsWith("/imgs/") ? name.substring(6) : name.startsWith("/") ? name.substring(1) : name;
        Path file=root.resolve(relative).normalize();
        Path owner=root.resolve("blogs/"+UserContext.getUserId()).normalize();
        if(!file.startsWith(owner) || !file.startsWith(root) || !Files.isRegularFile(file)) { return Result.fail("文件不存在或不属于当前用户"); }
        if(!file.toRealPath().startsWith(root.toRealPath())) { return Result.fail("文件路径无效"); }
        Files.delete(file); return Result.ok();
    }
}
