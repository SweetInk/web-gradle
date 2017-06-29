/**
 * 
 */
package com.zexi.service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;

import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.peanut.commons.utils.DateUtil;
import com.zexi.bean.Package;
import com.zexi.bean.ResponseMessage;
import com.zexi.bean.ServerConfig;
import com.zexi.bean.message.rep.TableListRep;
import com.zexi.dao.AaptDAO;
import com.zexi.utils.FileUtil;

/**
 * @author yulele
 *
 * @time 2017年6月15日 上午11:08:29
 */
@Service
public class AaptService {
    @Autowired
    private AaptDAO aaptDAO;
    @Autowired
    private ServerConfig serverConfig;
    
    private Logger log = LoggerFactory.getLogger(AaptService.class);
    
    private ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<String, Object>();
    
    /**
     * 打包历史展示
     * @param stxt  主题名
     * @param offset
     * @param limit
     * @return
     */
    public TableListRep getHistory(String stxt, int offset, int limit) {
        TableListRep tableListRep = new TableListRep();
        if (stxt==null) {
            stxt = "";
        }
        List<Package> p = aaptDAO.getHistory(stxt.trim(),offset, limit);
        int total = aaptDAO.getDataCount(stxt.trim());
        
        tableListRep.setRows(p);
        tableListRep.setTotal(total);
        return tableListRep;
    }
    
    /**
     * 生成apk包
     * @param p
     * @return
     * 1.调用gradle进行打包
     * 2.打包记录入库
     */
    public synchronized Map<String,Object> generateApk(Package p) {
        String projectPath = serverConfig.getProjectPath();
        String url = serverConfig.getApkFilePrefix()
                    +DateUtil.forDefaultDatetime(new Date())
                    +"_"+p.getThemeName()+".apk";
        p.setUrl(url);
        int newId = 0;
        boolean flag = buildLauncher(projectPath,p);//判断打包成功或失败
        if(flag){//打包成功
            if(isExists(p.getThemeName())){//以前打过包，更新记录时间
                aaptDAO.updateTime(p.getThemeName());
            }else{
                newId = aaptDAO.generateApk(p);
            }
        }
        map.put("isSuccess", flag);
        map.put("cid", newId+"");
        return map;
    }
    
    /**
     * 判断主题包之前是否打过
     * true 打过  false 未打过
     * @return
     */
    private boolean isExists(String themeName) {
        Integer count = aaptDAO.getHistoryByName(themeName);
        return count != 0;
    }
    
    /**
     * 上传zip数据到服务器,并解压
     * @param request
     * @param file
     * @return
     */
    public ResponseMessage uploadZip(HttpServletRequest request, MultipartFile file) {
        ResponseMessage rm = new ResponseMessage();
        if(!file.isEmpty()){
            String name = file.getOriginalFilename();
            String realPath = serverConfig.getZipPath()+name;
            String dirName = name.substring(0, name.lastIndexOf("."));
            rm.setData(dirName);
            String targetPath = serverConfig.getZipDecompressPath()+"/"+dirName;
            fileHandle(targetPath);
           
            File zipFile = new File(realPath);
            Process pro = null;
            try {
                file.transferTo(zipFile);
                //解压zip包到指定目录
                if(zipFile.exists()){
                    FileUtil.zipDecompress(realPath,targetPath);
                }
            }catch (Exception e) {
                log.info("上传或解压zip出错");
                rm.setMessage("error");
                rm.setStatus(0);
                e.printStackTrace();
            }finally {
                if(pro!=null){
                    pro.exitValue();
                    pro.destroy();
                }
            }
        }
        return rm;
    }
    
    /**
     * 调用gradle
     * @param projectPath  项目根目录
     * @param pkg          打包相关配置
     */
    public static boolean  buildLauncher(String projectPath,Package pkg) {
        ProjectConnection connection = GradleConnector.newConnector().
                forProjectDirectory(new File(projectPath)).connect();
        String buildResult = "";
        try {
            BuildLauncher build = connection.newBuild();
            build.forTasks("clean","assemble"+pkg.getThemeName()+"Release");
            List<String> buildArgs = new ArrayList<String>();
            buildArgs.add("--parallel");//并行
            buildArgs.add("-P" + "APPLICATIONID="+pkg.getApplicationId());
            buildArgs.add("-P" + "THEME_NAME="+pkg.getThemeName());
            buildArgs.add("-P" + "THEME_DESC="+pkg.getThemeDesc());
            buildArgs.add("-P" + "THEME_CHANNEL="+pkg.getThemeChannel());
            
            build.withArguments(buildArgs.toArray(new String[] {}));
          
            
            ByteArrayOutputStream baoStream = new ByteArrayOutputStream(1024);
            PrintStream cacheStream = new PrintStream(baoStream);
            //PrintStream oldStream = System.out;
            System.setOut(cacheStream);//不打印到控制台
            
            build.setStandardOutput(System.out);
            build.setStandardError(System.err);
            build.run();
            
            buildResult = baoStream.toString();
            //System.setOut(oldStream);//还原到控制台输出
            
            //打印写入文件
            FileOutputStream fo = new FileOutputStream("./gradle.out",true);
            System.setOut(new PrintStream(fo));
            
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            connection.close();
        }
        return buildResult.contains("BUILD SUCCESSFUL");
    }
    
    public static void main(String[] args) {
        String path = "D:/DevelopTools/workspace/NewAutoTheme";
        Package p = new Package();
        p.setApplicationId("com.mycheering.launcher.auto.xiaomi");
        p.setThemeChannel("100041");
        p.setThemeName("theme_xiaomi");
        p.setThemeDesc("小米主题");
        System.out.println(buildLauncher(path,p));
    }
    
    
    /**
     * 文件处理
     * @param resPath 资源路径
     * @param dirPath 目标目录
     */
    private synchronized void fileHandle(String dirPath) {
        File file = new File(dirPath);
        if(file.exists()){
            if(!file.isDirectory()){
                log.info("target is not dir!");
                return;
            }
            FileUtil.deleteAll(file);
        }else{
            //创建目录
            file.mkdir();
        }
    }

    /**
     * @param parseInt
     */
    public void delPack(int id) {
        //先删打包记录
        //再删apk包
        aaptDAO.delPack(id);
    }
}