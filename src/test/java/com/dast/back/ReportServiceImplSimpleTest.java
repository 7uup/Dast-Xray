package com.dast.back;

import com.dast.back.Bean.Task;
import com.dast.back.Bean.TaskReport;
import com.dast.back.Bean.WebHook;
import com.dast.back.Service.ReportService;
import com.dast.back.Service.TaskService;
import com.dast.back.Service.WebHookService;
import com.dast.back.mapper.ReportMapper;
import com.dast.back.mapper.TaskMapper;
import com.dast.back.util.CustomWebhookSender;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


@RunWith(SpringRunner.class)
@SpringBootTest(classes = AppRun.class)
public class ReportServiceImplSimpleTest {



    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private WebHookService webHookService;

    @Autowired
    private ReportMapper reportService;

    @Test
    public void runCrawlergo() throws IOException {
        System.out.println(taskService);
        taskService.startTask("02d6949e-11ee-45f0-a2a6-9618dd996363",1);
//        List<Task> taskInfo = selectByGroupId("02d6949e-11ee-45f0-a2a6-9618dd996363");
//        CrawlergoManager mgr = new CrawlergoManager();
//        mgr.startCrawlergo("C:\\Users\\15305\\Downloads\\xray_windows_amd64\\crawlergo_win_amd64.exe","C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe","http://139.224.187.50:8765/","113.45.199.25:7777",null);

    }

    @Test
    public void testlist(){
        List<Task> tasks = taskService.getTaskList(0, 10,1);
        for (Task task : tasks) {
            System.out.println(task);
        }
    }






    //CrawlergoManager.CrawlergoProcessInfo info = mgr.startCrawlergo(crawlergo_path,chromepath,url,xrayProxyLis,null);
}