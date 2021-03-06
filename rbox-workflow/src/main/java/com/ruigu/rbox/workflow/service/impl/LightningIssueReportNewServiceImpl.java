package com.ruigu.rbox.workflow.service.impl;

import com.UpYun;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.ruigu.rbox.cloud.kanai.util.ConvertUtil;
import com.ruigu.rbox.cloud.kanai.web.page.PageImpl;
import com.ruigu.rbox.workflow.config.ReportEmailTargetParamProperties;
import com.ruigu.rbox.workflow.config.UpyunConfig;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.feign.PassportFeignClient;
import com.ruigu.rbox.workflow.model.ServerResponse;
import com.ruigu.rbox.workflow.model.dto.DepartmentIssueDTO;
import com.ruigu.rbox.workflow.model.dto.LightningIssueReportNewDTO;
import com.ruigu.rbox.workflow.model.dto.WholeReportDTO;
import com.ruigu.rbox.workflow.model.enums.QueryFormType;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.model.vo.EmailAttachment;
import com.ruigu.rbox.workflow.model.vo.GroupAndUserVO;
import com.ruigu.rbox.workflow.model.vo.lightning.AllUserDepartmentNameVO;
import com.ruigu.rbox.workflow.model.vo.lightning.DepartmentIssueVO;
import com.ruigu.rbox.workflow.model.vo.lightning.LightningIssueReportNewVO;
import com.ruigu.rbox.workflow.model.vo.lightning.UserDepartmentsAndNameVO;
import com.ruigu.rbox.workflow.repository.LightningIssueApplyRepository;
import com.ruigu.rbox.workflow.repository.LightningIssueLogRepository;
import com.ruigu.rbox.workflow.service.LightningIssueReportNewService;
import com.ruigu.rbox.workflow.service.QuestNoticeService;
import com.upyun.UpException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author caojinghong
 * @date 2020/01/09 15:15
 */
@Service
@Slf4j
public class LightningIssueReportNewServiceImpl implements LightningIssueReportNewService {
    @Autowired
    private LightningIssueLogRepository logRepository;
    @Autowired
    private UpyunConfig upyunConfig;
    @Autowired
    private QuestNoticeService questNoticeService;
    @Autowired
    private PassportFeignClient passportFeignClient;
    @Autowired
    private LightningIssueApplyRepository applyRepository;
    @Value("${rbox.workflow.lightning.report.user-id.targetList1}")
    private List<Integer> targetList1;
    @Autowired
    private ReportEmailTargetParamProperties reportEmailTargetParamProperties;

    @Override
    public ServerResponse exportExcel(Integer queryType, LocalDateTime startQueryTime, LocalDateTime endQueryTime) throws Exception {
        LocalDate queryMonth;
        String name = "?????????????????????-";
        LocalDateTime start;
        LocalDateTime end;
        if (QueryFormType.LAST_MONTH.getCode().equals(queryType)) {
            // ?????????????????????
            queryMonth = LocalDate.now().minusMonths(1);
            name = name + queryMonth.getYear() + "???" + queryMonth.getMonthValue() + "???";
            start = LocalDateTime.of(queryMonth.with(TemporalAdjusters.firstDayOfMonth()), LocalTime.MIN);
            end = LocalDateTime.of(queryMonth.with(TemporalAdjusters.lastDayOfMonth()), LocalTime.MAX);
        } else if (QueryFormType.CURRENT_MONTH.getCode().equals(queryType)) {
            queryMonth = LocalDate.now();
            name = name + queryMonth.getYear() + "???" + queryMonth.getMonthValue() + "???";
            start = LocalDateTime.of(queryMonth.with(TemporalAdjusters.firstDayOfMonth()), LocalTime.MIN);
            end = LocalDateTime.of(queryMonth.with(TemporalAdjusters.lastDayOfMonth()), LocalTime.MAX);
        } else if (QueryFormType.LAST_DAY.getCode().equals(queryType)) {
            queryMonth = LocalDate.now().plusDays(-1);
            name = name + queryMonth.getYear() + "???" + queryMonth.getMonthValue() + "???" + queryMonth.getDayOfMonth() + "???";
            start = LocalDateTime.of(queryMonth, LocalTime.MIN);
            end = LocalDateTime.of(queryMonth, LocalTime.MAX);
        } else if (QueryFormType.CUSTOM_TIME.getCode().equals(queryType)) {
            if (startQueryTime == null || endQueryTime == null) {
                return ServerResponse.fail(ResponseCode.REQUEST_ERROR.getCode(),"??????????????????????????????!");
            }
            queryMonth = startQueryTime.toLocalDate();
            LocalDate endQueryDate = endQueryTime.toLocalDate();
            name = name + queryMonth.getYear() + "???" + queryMonth.getMonthValue() + "???" + queryMonth.getDayOfMonth() + "??????"+ endQueryDate.getYear() + "???" + endQueryDate.getMonthValue() + "???" + endQueryDate.getDayOfMonth() + "???";
            start = startQueryTime;
            end = endQueryTime;
        }
        else {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "queryType??????");
        }
        Date startTime = Date.from( start.atZone( ZoneId.systemDefault()).toInstant());
        Date endTime = Date.from( end.atZone( ZoneId.systemDefault()).toInstant());

        File tempFile = File.createTempFile(name, ".xlsx");

        ExcelWriter excelWriter = EasyExcel.write(tempFile).build();
        WriteSheet writeSheet1 = EasyExcel.writerSheet(0,"??????").head(WholeReportDTO.class).build();
        WriteSheet writeSheet2 = EasyExcel.writerSheet(1, "??????").head(LightningIssueReportNewVO.class).build();
        WriteSheet writeSheet3 = EasyExcel.writerSheet(2,"?????????").head(DepartmentIssueVO.class).build();

        Pageable page = PageRequest.of(0,Integer.MAX_VALUE);
        // ?????????????????????
        Page<Map> wholeMaps= applyRepository.queryWholeData(startTime, endTime, page);
        List<WholeReportDTO> sheet1Data = PageImpl.of(wholeMaps, x -> ConvertUtil.mapToObject(x, WholeReportDTO.class)).getContent();
        WholeReportDTO wholeReportDto = sheet1Data.get(0);
        if (wholeReportDto.getIssueCount().equals(0)) {
            wholeReportDto.setIssueDemandCount(0);
            wholeReportDto.setEvaluateScoreCount(0);
            excelWriter.write(sheet1Data, writeSheet1);
            excelWriter.finish();
            String upYunFilePath = uploadFile(tempFile, name);
            return ServerResponse.ok("???????????????????????????????????????????????? "+upYunFilePath);
        }
        excelWriter.write(sheet1Data, writeSheet1);

        // ?????????????????????
        Page<Map> maps = logRepository.queryReportFormNew(startTime, endTime, page);
        List<LightningIssueReportNewDTO> reportDtos = PageImpl.of(maps, x -> ConvertUtil.mapToObject(x, LightningIssueReportNewDTO.class)).getContent();

        // ????????????????????????
        if (CollectionUtils.isNotEmpty(reportDtos)) {
            List<Integer> userIds = new ArrayList<>();
            for (LightningIssueReportNewDTO everyData: reportDtos) {
                userIds.add(everyData.getUserId());
            }
            ServerResponse<List<UserDepartmentsAndNameVO>> listServerResponse = passportFeignClient.queryUserDepartmentsAndName(userIds);
            List<UserDepartmentsAndNameVO> userList = listServerResponse.getData();
            if (listServerResponse.getCode() != ResponseCode.SUCCESS.getCode() || CollectionUtils.isEmpty(userList)) {
                throw new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????????????????????????????");
            }

            Map<Integer, UserDepartmentsAndNameVO> collect = handleListToMap(userList);
            List<LightningIssueReportNewVO> sheet2Data = new ArrayList<>();
            for (LightningIssueReportNewDTO everyData: reportDtos) {
                Integer userId = everyData.getUserId();
                UserDepartmentsAndNameVO userDepartmentsAndNameVO = collect.get(userId);
                if (userDepartmentsAndNameVO == null) {
                    log.error("?????? ????????????????????????????????????????????????????????????????????????id: {}",userId);
                    continue;
                }
                LightningIssueReportNewVO reportVO = new LightningIssueReportNewVO();
                BeanUtils.copyProperties(everyData, reportVO);
                reportVO.setPersonName(userDepartmentsAndNameVO.getNickname());
                reportVO.setFirstLevelDepartmentName(userDepartmentsAndNameVO.getFirstLevelDepartment());
                reportVO.setSecondLevelDepartmentName(userDepartmentsAndNameVO.getSecondaryLevelDepartment());
                if (StringUtils.isBlank(reportVO.getSecondLevelDepartmentName())) {
                    reportVO.setSecondLevelDepartmentName("???");
                }
                sheet2Data.add(reportVO);
            }
            List<LightningIssueReportNewVO> returnData= sheet2Data.stream().sorted(Comparator.comparing(LightningIssueReportNewVO::getFirstLevelDepartmentName).thenComparing(LightningIssueReportNewVO::getSecondLevelDepartmentName)).collect(Collectors.toList());
            excelWriter.write(returnData, writeSheet2);

        }
        // ??????????????????????????????
        Page<Map> departmentMaps = applyRepository.queryDepartmentData(startTime, endTime, page);
        List<DepartmentIssueDTO> departmentContent = PageImpl.of(departmentMaps, x -> ConvertUtil.mapToObject(x, DepartmentIssueDTO.class)).getContent();
        Map<String, GroupInfo> groupAndUserInfo = getGroupAndUserInfo();
        if (CollectionUtils.isNotEmpty(departmentContent) && groupAndUserInfo != null) {
            List<DepartmentIssueVO> sheet3Data = new ArrayList<>();
            for (DepartmentIssueDTO departmentIssueDTO : departmentContent) {
                GroupInfo groupInfo = groupAndUserInfo.get(String.valueOf(departmentIssueDTO.getSecondLevelDepartmentId()));
                DepartmentIssueVO departmentIssueVO = new DepartmentIssueVO();
                BeanUtils.copyProperties(departmentIssueDTO, departmentIssueVO);
                departmentIssueVO.setFirstLevelDepartmentName(groupInfo.getFirstLevelGroup());
                departmentIssueVO.setSecondLevelDepartmentName(groupInfo.getSecondLevelGroup());
                sheet3Data.add(departmentIssueVO);
            }
            excelWriter.write(sheet3Data, writeSheet3);
        }

        // ???????????????finish ??????????????????
        excelWriter.finish();
        // ????????????????????????
        String upYunFilePath = uploadFile(tempFile, name);

        // ????????????
        tempFile.delete();

        // ????????????
        List<Integer> targetIds = targetList1;
        List<Integer> ccTargetIds = reportEmailTargetParamProperties.getCcTargetList();
        Set<EmailAttachment> emailAttachments = new HashSet<>();
        EmailAttachment emailAttachment = new EmailAttachment();
        emailAttachment.setName(name+".xlsx");
        emailAttachment.setUrl(upYunFilePath);
        emailAttachments.add(emailAttachment);
        String title = name;
        String content = "????????????!";
        return questNoticeService.sendEmailNotice(null, title, content, targetIds, ccTargetIds, emailAttachments);
    }

    @Override
    public ServerResponse getAllUserDepartmentInfo() throws IOException {
        String name = "?????????????????????????????????????????????";
        File tempFile = File.createTempFile(name, ".xlsx");

        ExcelWriter excelWriter = EasyExcel.write(tempFile).build();
        WriteSheet writeSheet = EasyExcel.writerSheet(0,"????????????").head(AllUserDepartmentNameVO.class).build();
        List<Integer> userIds = new ArrayList<>();
        for (int i = 658; i < 2074; i++) {
            userIds.add(i);
        }
        ServerResponse<List<UserDepartmentsAndNameVO>> listServerResponse = passportFeignClient.queryUserDepartmentsAndName(userIds);
        List<UserDepartmentsAndNameVO> userList = listServerResponse.getData();
        if (listServerResponse.getCode() != ResponseCode.SUCCESS.getCode() || CollectionUtils.isEmpty(userList)) {
            throw new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????????????????????????????");
        }
        Set<Integer> userIdsSet = userList.stream().map(UserDepartmentsAndNameVO::getUserId).collect(Collectors.toSet());
        Map<Integer, UserDepartmentsAndNameVO> collect = handleListToMap(userList);
        List<AllUserDepartmentNameVO> userInfoList = new ArrayList<>();
        for (Integer everyUserId: userIdsSet) {
            UserDepartmentsAndNameVO userDepartmentsAndNameVO = collect.get(everyUserId);
            AllUserDepartmentNameVO everyUser = new AllUserDepartmentNameVO();
            everyUser.setUserId(everyUserId);
            everyUser.setPersonName(userDepartmentsAndNameVO.getNickname());
            everyUser.setFirstLevelDepartmentName(userDepartmentsAndNameVO.getFirstLevelDepartment());
            everyUser.setSecondLevelDepartmentName(userDepartmentsAndNameVO.getSecondaryLevelDepartment());
            userInfoList.add(everyUser);
        }
        excelWriter.write(userInfoList, writeSheet);
        // ???????????????finish ??????????????????
        excelWriter.finish();
        return ServerResponse.ok();
    }

    /**
     * ????????????
     * @param filePrefix ??????????????????????????????????????????properties????????????
     * @param fileName ????????????
     * @return ???????????????????????????
     */
    private String getFilePath(String filePrefix,String fileName) {
        return filePrefix + fileName + UUID.randomUUID().toString()+".xlsx";
    }

    /**
     * ????????????????????????
     * @param tempFile ????????????
     * @param fileName ????????????
     * @return ????????????????????????????????????????????????????????????
     * @throws IOException IO??????
     * @throws UpException ???????????????
     */
    private String uploadFile(File tempFile, String fileName) throws IOException, UpException {
        log.info("????????????????????????");
        String filePath =  getFilePath(upyunConfig.getFilePathPrefix(),fileName);
        UpYun upyun = new UpYun(upyunConfig.getBucketName(), upyunConfig.getUsername(), upyunConfig.getPassword());
        boolean isSuccess = upyun.writeFile(filePath, tempFile, true);
        if (!isSuccess) {
            throw new UpException("????????????????????????");
        }
        log.info("????????????????????????");
        return upyunConfig.getPrefix() + filePath;
    }

    /**
     * ??????passport??????????????????????????????????????????
     * @return ??????map???????????????????????????Id,?????????????????????????????????
     */
    private Map<String, GroupInfo> getGroupAndUserInfo(){
        ServerResponse<List<GroupAndUserVO>> groupAndUserInfoResponse = passportFeignClient.getGroupAndUserInfo(null, null);
        if (groupAndUserInfoResponse.getCode() != ResponseCode.SUCCESS.getCode()) {
            log.error("??????????????????????????????????????????");
            return null;
        }
        List<GroupAndUserVO> groupAndUserInfo = groupAndUserInfoResponse.getData();
        // ????????????id map
        Map<String, GroupInfo> map = new HashMap<>(32);
        for (GroupAndUserVO info : groupAndUserInfo) {
            if (CollectionUtils.isEmpty(info.getChildren())) {
                continue;
            }
            for (GroupAndUserVO subInfo : info.getChildren()) {
                if (subInfo.getValue() != null) {
                    GroupInfo groupInfo = new GroupInfo();
                    groupInfo.setFirstLevelGroup(info.getLabel());
                    groupInfo.setSecondLevelGroup(subInfo.getLabel());
                    map.put(subInfo.getValue().toString(), groupInfo);
                }
            }
        }
        return map;
    }

    /**
     * ??????????????????????????????????????????????????????????????????????????????????????????map??????????????????
     * @param userList ??????????????????
     * @return ???????????????Map
     */
    private Map<Integer, UserDepartmentsAndNameVO> handleListToMap(List<UserDepartmentsAndNameVO> userList){
        return userList.stream().collect(Collectors.toMap(
                UserDepartmentsAndNameVO::getUserId, Function.identity(),
                (user1, user2) -> {
                    String firstLevelDepartment1 = user1.getFirstLevelDepartment();
                    String firstLevelDepartment2 = user2.getFirstLevelDepartment();
                    String secondaryLevelDepartment1 = user1.getSecondaryLevelDepartment();
                    String secondaryLevelDepartment2 = user2.getSecondaryLevelDepartment();
                    String second = "/";
                    if (StringUtils.isBlank(secondaryLevelDepartment1)) {
                        user1.setSecondaryLevelDepartment("");
                        second = "";
                    }
                    if (StringUtils.isBlank(secondaryLevelDepartment2)) {
                        user2.setSecondaryLevelDepartment("");
                        second = "";
                    }
                    String secondName = user1.getSecondaryLevelDepartment() + second + user2.getSecondaryLevelDepartment();
                    user1.setSecondaryLevelDepartment(secondName);

                    String first = "/";
                    if (StringUtils.isEmpty(firstLevelDepartment1)) {
                        user1.setFirstLevelDepartment("");
                        first = "";
                    }
                    if (StringUtils.isEmpty(firstLevelDepartment2)) {
                        user2.setFirstLevelDepartment("");
                        first = "";
                    }
                    String firstName = user1.getFirstLevelDepartment() + first + user2.getFirstLevelDepartment();
                    user1.setFirstLevelDepartment(firstName);
                    return user1;
                }));
    }
    @Data
    private static class GroupInfo {
        private String firstLevelGroup;
        private String secondLevelGroup;
    }
}
