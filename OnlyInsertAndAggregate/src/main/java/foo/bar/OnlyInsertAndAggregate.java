package foo.bar;

import com.beust.jcommander.JCommander;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;

import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Исполняемый класс
 *
 * @author Sergey.Titkov
 * @version 001.00
 * @since 001.00
 */
public class OnlyInsertAndAggregate {

  public static void main(String[] args) {
    try {
      new ConsoleCodingSettings();
    } catch (UnsupportedEncodingException e) {
      return;
    }

    // Разбираем командною строку.
    CommandLineParameters commandLineParameters = new CommandLineParameters();
    JCommander jCommander;
    try {
      jCommander = new JCommander(commandLineParameters, args);
    } catch (Exception e) {
      System.err.println("Ошибка в аргументах командоной строки: " + Arrays.toString(args));
      jCommander = new JCommander(commandLineParameters);
      jCommander.setProgramName("OnlyInsertAndAggregate", "Многопоточное обновление значения баланса в кассандре.");
      jCommander.usage();
      return;

    }

    if (commandLineParameters.help) {
      jCommander.setProgramName("OnlyInsertAndAggregate", "Многопоточное обновление значения баланса в кассандре.");
      jCommander.usage();
      return;
    }

    SimpleClient client = new SimpleClient();
    client.connect(commandLineParameters.host);
    Session session = client.getSession();

    Long clnt = Long.valueOf(commandLineParameters.client);

    // Прницип работы UpdateBalance это LTS, создали и передели в нитки
    InsertValue insertValue = new InsertValue(session, 10);

    int numberThread = commandLineParameters.numberOfThread < 0 || commandLineParameters.numberOfThread > 128 ? 10 : commandLineParameters.numberOfThread;
    int timeToWork = commandLineParameters.time < 0 || commandLineParameters.time > 86400 ? 5 : commandLineParameters.time;

    CountDownLatch countDownLatch = new CountDownLatch(numberThread);

    List<ProcessInsertValue> listProcessUpdateBalance = new ArrayList<>();
    for (int i = 0; i < numberThread; i++) {
      listProcessUpdateBalance.add(new ProcessInsertValue(timeToWork, countDownLatch, insertValue, clnt));
    }
    // Удаляем данные
    session.execute(String.format("delete from test_data_mart.counters_values where main_id = %s;", clnt));

    for (int i = 0; i < numberThread; i++) {
      System.out.println("Запускаем нить:" + i);
      listProcessUpdateBalance.get(i).start();
    }
    try {
      countDownLatch.await();
    } catch (InterruptedException e) {
      System.err.println("Ошибка при ожидании завершения нитей: " + e.getMessage());
    }

    SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSS");

    System.out.println("Закончили");
    System.out.println("Последние значения балансов для нити:");
    String header = "%-36s\t%-20s\t%-20s\t%-20s\t%-20s\t%-28s\t%-20s";
    String line = "------------------------------------\t" +
      "--------------------\t" +
      "--------------------\t" +
      "--------------------\t" +
      "--------------------\t" +
      "----------------------------\t" +
      "--------------------\t";
    System.out.println(
      String.format(
        header + "\n%s",
        "UUID",
        "VOL 01",
        "VOL 02",
        "VOL 03",
        "WriteTimeoutException",
        "Не удалось обновить счетчики",
        "Обновлений в секунду",
        line
      )
    );

    long incrementVol01 = 0;
    long incrementVol02 = 0;
    long incrementVol03 = 0;
    long numberUpdates = 0;

    for (ProcessInsertValue item : listProcessUpdateBalance) {
      System.out.println(
        String.format(
          header,
          item.getThreadUUID(),
          item.getIncrementVol01(),
          item.getIncrementVol02(),
          item.getIncrementVol03(),
          item.getNumberOfWriteTimeoutException(),
          item.getNumberOfErrorUpdateBalance(),
          Math.round(item.getNumberUpdates() / timeToWork)
        )
      );
      incrementVol01 += item.getIncrementVol01();
      incrementVol02 += item.getIncrementVol02();
      incrementVol03 += item.getIncrementVol03();
      numberUpdates += Math.round(item.getNumberUpdates() / timeToWork);
    }
    System.out.println(
      String.format(
        "%s",
        line
      )
    );
    System.out.println(
      String.format(
        header,
        "",
        incrementVol01,
        incrementVol02,
        incrementVol03,
        "",
        "",
        numberUpdates
      )
    );

    ResultSet results;
    long testVol01 = 0;
    long testVol02 = 0;
    long testVol03 = 0;

    results = session.execute(
      String.format(
        "select vol_01, vol_02, vol_03 from test_data_mart.counters_values where main_id = %s;", clnt
      )
    );
    for (Row row : results) {
      testVol01 += row.getLong("vol_01");
      testVol02 += row.getLong("vol_02");
      testVol03 += row.getLong("vol_03");
    }
    System.out.println(String.format("%s", line));

    System.out.println("Кассандра");
    System.out.println(
      String.format(
        header,
        "Кассандра",
        testVol01,
        testVol02,
        testVol03,
        "",
        "",
        ""
      )
    );
    System.out.println(String.format("%s", line));
    client.close();

  }


}