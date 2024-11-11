package com.zxc.publics.listener;

import com.alibaba.excel.EasyExcelFactory;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.CellData;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.zxc.publics.annotation.ImportExcelField;
import com.zxc.publics.exception.ExcelHeadException;
import com.zxc.publics.functionalInterface.ThrowingConsumer;
import com.zxc.publics.functionalInterface.ThrowingFunction;
import com.zxc.publics.functionalInterface.ThrowingRunnable;
import com.zxc.publics.handler.CustomCellColorWriteHandler;
import com.zxc.publics.handler.CustomCellWriteHandler;
import com.zxc.publics.util.StringUtil;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.zxc.publics.constant.ExcelImportConstant.*;

/**
 * @param: <T>
 * @Author: omnipotentQIQI
 * @Date: 2024/2/2 14:00
 * @Description: excel导入数据监听器
 */
@Data
@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class ExcelImportDataListener<T> extends AnalysisEventListener<Map<Integer, String>> {

    // excel表头行号
    private int excelHeadRowIndex;

    // 实体类类型
    private Class<?> clazz;

    // 导入开始前自定义操作
    private ThrowingRunnable<Exception> beforeImportMethod;

    // 数据导入方法
    private ThrowingConsumer<T, Exception> importMethod;

    // 校验数据是否合法方法
    private ThrowingFunction<T, Boolean, Exception> checkDataMethod;

    // 导入结束后自定义操作
    private ThrowingRunnable<Exception> afterImportMethod;

    // 导入数据使用的线程池
    private ExecutorService executorService;

    // 单次读取数据量
    private int batchSize;

    // 新增成功数
    private AtomicInteger addNum = new AtomicInteger(0);

    // 校验失败数
    private AtomicInteger errorNum = new AtomicInteger(0);

    // 总处理数
    private AtomicInteger totalNum = new AtomicInteger(0);

    // 读取到内存中的数据集
    private List<Map<Integer, String>> dataList = new ArrayList<>();

    // 首次处理数据集标识
    private Boolean firstHandleDataFlag = true;

    // 非法数据集
    private List<Map<Integer, String>> errorList = new ArrayList<>();

    // 非法数据导出文件名
    private String illegalDataExportFileName;

    // excel字段名称行校验不合法异常
    private ExcelHeadException exception = null;

    // 导入数据的异步返回结果集合
    private List<CompletableFuture<Void>> successCompletableFutureList = new ArrayList<>();

    // excel写入
    private ExcelWriter excelWriter = null;

    // excel写入sheet
    private WriteSheet writeSheet = null;

    // 导入是否完成标识
    private Boolean importFinishFlag = false;

    // 读取到的表头数量
    private int headCount = 0;

    // 文件数据总条数
    private int maxAllowDataNum;

    // 导入开始时间
    private long startTime;

    // 导入结束时间
    private long endTime;

    public ExcelImportDataListener(int excelHeadRowIndex, Class<?> clazz, int batchSize, ThrowingRunnable<Exception> beforeImportMethod, ThrowingRunnable<Exception> afterImportMethod, ThrowingConsumer<T, Exception> importMethod, ThrowingFunction<T, Boolean, Exception> checkDataMethod, String illegalDataExportFileName, long startTime, int maxAllowDataNum) {
        this.excelHeadRowIndex = excelHeadRowIndex;
        this.clazz = clazz;
        this.batchSize = batchSize;
        this.beforeImportMethod = beforeImportMethod;
        this.importMethod = importMethod;
        this.checkDataMethod = checkDataMethod;
        this.afterImportMethod = afterImportMethod;
        this.illegalDataExportFileName = illegalDataExportFileName.endsWith(".xlsx") ? illegalDataExportFileName : illegalDataExportFileName + ".xlsx";
        this.startTime = startTime;
        this.executorService = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors() + 1, (Runtime.getRuntime().availableProcessors() + 1) * 2, 3L, TimeUnit.MINUTES, new LinkedBlockingQueue<>(500), new NamedThreadFactory("excelImportListener"), new ThreadPoolExecutor.CallerRunsPolicy());
        this.maxAllowDataNum = maxAllowDataNum;
    }

    public ExcelImportDataListener(int excelHeadRowIndex, Class<?> clazz, int batchSize, ThrowingRunnable<Exception> beforeImportMethod, ThrowingRunnable<Exception> afterImportMethod, ThrowingConsumer<T, Exception> importMethod, ThrowingFunction<T, Boolean, Exception> checkDataMethod, String illegalDataExportFileName, long startTime, ExecutorService executorService, int maxAllowDataNum) {
        this.excelHeadRowIndex = excelHeadRowIndex;
        this.clazz = clazz;
        this.batchSize = batchSize;
        this.beforeImportMethod = beforeImportMethod;
        this.importMethod = importMethod;
        this.checkDataMethod = checkDataMethod;
        this.afterImportMethod = afterImportMethod;
        this.illegalDataExportFileName = illegalDataExportFileName.endsWith(".xlsx") ? illegalDataExportFileName : illegalDataExportFileName + ".xlsx";
        this.startTime = startTime;
        this.executorService = executorService;
        this.maxAllowDataNum = maxAllowDataNum;
    }

    /**
     * 校验Excel文件表头字段名是否合法
     *
     * @param headMap
     * @param context
     */
    @Override
    public void invokeHead(Map<Integer, CellData> headMap, AnalysisContext context) {
        log.info("ExcelImportDataListener invokeHead >>> headCount = {}, headMap = {}", headCount, headMap);
        if (headCount == excelHeadRowIndex - 1 &&
                !Objects.equals(getAllExcelFieldNames(), headMap.values().stream().map(CellData::getStringValue).collect(Collectors.toList())))
            exception = new ExcelHeadException("导入的Excel文件表头字段不合法！");
        else headCount++;
        if ((context.getTotalCount() == null ? 0 : context.getTotalCount()) > maxAllowDataNum)
            throw new RuntimeException("导入的数据条数超过最大阈值 " + maxAllowDataNum + " ！");
    }

    /**
     * 文件数据读取
     *
     * @param integerStringMap
     * @param analysisContext
     */
    @Override
    public void invoke(Map<Integer, String> integerStringMap, AnalysisContext analysisContext) {

        dataListAdd(integerStringMap);

    }

    /**
     * 所有数据全部读取完毕后的处理
     *
     * @param analysisContext
     */
    @Override
    public void doAfterAllAnalysed(AnalysisContext analysisContext) {

        try {
            handleDataList();
            handleErrorList();
            afterImportMethod.run();
            log.info("总数据量: {}, 导入数量: {}, 错误数量: {}", totalNum.get(), addNum.get(), errorNum.get());
        } catch (Exception e) {
            log.error("doAfterAllAnalysed error", e);
        }
        importFinishFlag = true;
        endTime = System.currentTimeMillis();

    }

    /**
     * 获取数据导入耗时
     */
    public Long getImportTime() {
        return importFinishFlag ? endTime - startTime : null;
    }

    /**
     * 判断导入是否已完成
     *
     * @return
     */
    public Boolean isFinish() {
        return importFinishFlag;
    }

    /**
     * 本地数据集数据处理
     */
    private void handleDataList() {

        if (dataList.isEmpty()) return;
        for (int i = 0; i < dataList.size(); i++) {
            if (exception != null) throw new RuntimeException(exception);
            if (firstHandleDataFlag && i == 0) {
                try {
                    beforeImportMethod.run();
                } catch (Exception e) {
                    log.error("beforeImportMethod error", e);
                }
            }
            int finalI = i;
            successCompletableFutureList.add(CompletableFuture.runAsync(() -> {
                try {
                    importThisData(dataList.get(finalI));
                } catch (Exception e) {
                    log.error("handleDataList error", e);
                }
            }, executorService));
        }
        // 将首次处理数据集标识置为false
        firstHandleDataFlag = false;
        // 等待所有线程执行完毕
        CompletableFuture.allOf(successCompletableFutureList.toArray(new CompletableFuture[0])).join();
        // 清空异步返回结果集合
        successCompletableFutureList.clear();
        // 清空数据集
        dataList.clear();

    }

    /**
     * 核心方法：单条数据处理逻辑
     *
     * @param integerStringMap
     * @throws Exception
     */
    private void importThisData(Map<Integer, String> integerStringMap) throws Exception {

        T t = getData(integerStringMap);
        log.info("data: {}", t);
        try {
            if (checkDataMethod.apply(t)) {
                importMethod.accept(t);
                // 记录新增成功数
                addNum.incrementAndGet();
                // 记录总处理数
                totalNum.incrementAndGet();
            } else {
                errorListAdd(reloadThisMap(integerStringMap));
            }
        } catch (Exception e) {
            log.error("checkDataMethod or importMethod error", e);
            errorListAdd(reloadThisMap(integerStringMap));
        }

    }

    /**
     * 对需要保证唯一性的数据进行校验
     *
     * @param integerStringMap
     * @return
     */
    private Boolean checkUnique(Map<Integer, String> integerStringMap) {

        AtomicBoolean flag = new AtomicBoolean(false);
        Arrays.stream(clazz.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(ImportExcelField.class) && field.getAnnotation(ImportExcelField.class).unique())
                .map(field -> field.getAnnotation(ImportExcelField.class).order())
                .forEach(order -> {
                    if (flag.get()) return;
                    String value = integerStringMap.get(order);
                    if (StringUtils.isBlank(value)) return;
                    if (dataList.stream().anyMatch(map -> value.equals(map.get(order)))) {
                        try {
                            errorListAdd(reloadThisMap(integerStringMap));
                        } catch (Exception e) {
                            log.error("checkUnique errorListAdd error", e);
                        }
                        flag.set(true);
                    }
                });
        return flag.get();

    }

    /**
     * 按照@ExcelField注解的属性值对字段值进行标记
     *
     * @param integerStringMap
     * @return
     */
    private Map<Integer, String> reloadThisMap(Map<Integer, String> integerStringMap) {

        Arrays.stream(clazz.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(ImportExcelField.class))
                .forEach(field -> {
                    ImportExcelField annotation = field.getAnnotation(ImportExcelField.class);
                    integerStringMap.put(annotation.order(), StringUtil.valueToStr(integerStringMap.get(annotation.order())) + ImportExcelField.ColorEnum.getSuffixByColor(annotation.errorColor()));
                });
        return integerStringMap;

    }

    /**
     * 将读取出来的单条map数据转换为对应的实体类对象
     *
     * @param data
     * @return
     * @throws Exception
     */
    @SuppressWarnings("unchecked")
    private T getData(Map<Integer, String> data) throws Exception {

        Object o = clazz.getConstructor().newInstance();
        getAllExcelFields().forEach(field -> {
            field.setAccessible(true);
            ImportExcelField annotation = field.getAnnotation(ImportExcelField.class);
            try {
                field.set(o, reloadFileValue(field, StringUtil.isEmpty(data.get(annotation.order())) ? annotation.defaultValue() : data.get(annotation.order())));
            } catch (Exception e) {
                log.error("getData error", e);
            }
        });
        return (T) o;

    }

    /**
     * 校验失败数据集数据处理（输出excel文件）
     */
    private void handleErrorList() throws Exception {

        if (errorList.isEmpty()) return;
        // 初始化excel文件
        if (!initExcelFile()) throw new Exception("初始化excel文件失败！");
        // 写入非法数据
        errorList.forEach(map -> {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < map.size(); i++) {
                list.add(map.get(i));
            }
            excelWriter.write(Collections.singletonList(list), writeSheet);
            // 记录校验失败数
            errorNum.incrementAndGet();
            // 记录总处理数
            totalNum.incrementAndGet();
        });
        // 关闭文件流
        excelWriter.finish();
        log.info("handleErrorList success, filePath = {}", EXCEL_ILLEGAL_DATA_EXPORT_PATH + illegalDataExportFileName);
        // 清空数据集
        errorList.clear();

    }

    /**
     * 初始化excel文件
     *
     * @return
     * @throws IOException
     */
    private Boolean initExcelFile() throws IOException {

        if (excelWriter != null && writeSheet != null) return true;
        File pathFile = new File(EXCEL_ILLEGAL_DATA_EXPORT_PATH);
        String fileFullPath = EXCEL_ILLEGAL_DATA_EXPORT_PATH + illegalDataExportFileName;
        File file = new File(fileFullPath);
        if (!(pathFile.exists() || pathFile.mkdirs()) || !(file.exists() ? file.delete() && file.createNewFile() : file.createNewFile()))
            return false;
        this.excelWriter = EasyExcelFactory.write(fileFullPath).build();
        this.writeSheet = EasyExcelFactory.writerSheet(SHEET_NAME).registerWriteHandler(new CustomCellWriteHandler()).registerWriteHandler(new CustomCellColorWriteHandler()).build();
        for (int i = 0; i < excelHeadRowIndex - 1; i++) {
            excelWriter.write(Collections.singletonList(Collections.singletonList("校验失败数据")), writeSheet);
        }
        excelWriter.write(getExcelHead(), writeSheet);
        return true;

    }

    /**
     * 获取excel表头（校验失败数据输出的excel文件）
     *
     * @return
     */
    private List<List<String>> getExcelHead() {

        return Collections.singletonList(getAllExcelFieldNames());

    }

    /**
     * 本地数据集添加
     *
     * @param integerStringMap
     */
    private void dataListAdd(Map<Integer, String> integerStringMap) {

        // 校验数据集大小
        if (dataList.size() >= batchSize) {
            handleDataList();
        }
        // 校验数据在数据集中是否已存在
        if (checkUnique(integerStringMap)) {
            return;
        }
        // 添加数据到数据集
        dataList.add(integerStringMap);

    }

    /**
     * 校验失败数据集添加
     */
    private void errorListAdd(Map<Integer, String> integerStringMap) throws Exception {

        // 校验数据集大小
        if (errorList.size() >= batchSize) {
            handleErrorList();
        }
        // 添加数据到数据集
        errorList.add(integerStringMap);

    }

    /**
     * 获得实体类中所有加了@Excelfield注解的属性集合
     *
     * @return
     */
    private List<Field> getAllExcelFields() {

        return Arrays.stream(clazz.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(ImportExcelField.class))
                .sorted(Comparator.comparingInt(field -> field.getAnnotation(ImportExcelField.class).order()))
                .collect(Collectors.toList());

    }

    /**
     * 获得实体类中所有加了@Excelfield注解的字段名集合
     *
     * @return
     */
    private List<String> getAllExcelFieldNames() {

        return Arrays.stream(clazz.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(ImportExcelField.class))
                .map(field -> field.getAnnotation(ImportExcelField.class))
                .sorted(Comparator.comparingInt(ImportExcelField::order))
                .map(ImportExcelField::name)
                .collect(Collectors.toList());

    }

    /**
     * 手动处理文件中的字段值（避免反射强转报错）
     *
     * @param field
     * @param value
     * @return
     */
    private Object reloadFileValue(Field field, String value) throws ParseException {

        Class<?> fieldType = field.getType();
        if (fieldType.equals(Integer.class)) {
            return checkInteger(value) ? Integer.parseInt(value) : null;
        } else if (fieldType.equals(Long.class)) {
            return checkLong(value) ? Long.parseLong(value) : null;
        } else if (fieldType.equals(Double.class)) {
            return checkDecimal(value) ? Double.valueOf(value) : null;
        } else if (fieldType.equals(Float.class)) {
            return checkDecimal(value) ? Float.valueOf(value) : null;
        } else if (fieldType.equals(Boolean.class)) {
            return Boolean.valueOf(value);
        } else if (fieldType.equals(Date.class)) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(value);
        } else {
            return value;
        }

    }

    /**
     * 检查是否是小数
     *
     * @param value
     * @return
     */
    private Boolean checkDecimal(String value) {

        return value.matches("^(?!\\.)(?!.*\\.$)(?!.*\\..*\\.)[0-9]{1,3}(\\.[0-9]{1,3})?$");

    }

    /**
     * 检查是否是符合Integer类型的数字
     */
    private Boolean checkInteger(String value) {

        return value.matches("^[-\\+]?[\\d]*$") && (value.length() == 1 || !value.startsWith("0")) && Long.parseLong(value) <= Integer.MAX_VALUE;

    }

    /**
     * 检查是否是符合Long类型的数字
     */
    private Boolean checkLong(String value) {

        return value.matches("^[-\\+]?[\\d]*$") && (value.length() == 1 || !value.startsWith("0")) && Long.parseLong(value) <= Long.MAX_VALUE;

    }

}
