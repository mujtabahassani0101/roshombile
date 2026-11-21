#include <cmath>
#include <algorithm>
#include <iostream>
#include <vector>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include "omr/AnswerReader.hpp"

// A real issued sheet uses white answer circles on alternating black bands.
// Keep empty circles in this fixture so recovering black ink cannot invent marks.
int main() {
  bool ok = true;
  for (const float radius : {15.0f, 21.95f, 29.167f}) {
    for (const bool grayscale : {false, true}) {
     for (const int lighting : {0,1,2}) {
      for (const bool magentaPrint : {false,true}) {
      const auto printInk=magentaPrint ? cv::Scalar(120,25,180) : cv::Scalar(22,22,22);
      omr::OmrTemplate tpl;
      tpl.width = 480; tpl.height = 860;
      tpl.answers.enabled = true;
      tpl.answers.questionCount = 8;
      tpl.answers.options = {"1", "2", "3", "4"};
      tpl.answers.thresholds = {0.38f, 0.35f, 0.40f, 0.14f};
      tpl.answersRegion = {20, 20, 440, 820};
      omr::OmrAnswerBlockTemplate block;
      block.id = "striped"; block.questionStart = 1; block.questionCount = 8;
      block.bubbleRadius = radius;
      block.xByOption = {{"1",100}, {"2",190}, {"3",280}, {"4",370}};
      for (int row = 0; row < 8; ++row) block.yByRow.push_back(80 + row * 100);
      tpl.registration.rowY = block.yByRow;
      tpl.answers.blocks = {block};
      cv::Mat image(860, 480, CV_8UC3, cv::Scalar(245,245,245));
      for (int row = 0; row < 8; ++row) {
        const int y = cvRound(block.yByRow[row]);
        if (row != 2 && row != 7) cv::rectangle(image, {30,y-40,410,80}, magentaPrint ? printInk : cv::Scalar(38,38,38), cv::FILLED);
        for (const auto& [option,x] : block.xByOption) {
          cv::circle(image, {cvRound(x),y}, cvRound(radius), cv::Scalar(245,245,245), cv::FILLED, cv::LINE_AA);
          cv::circle(image, {cvRound(x),y}, cvRound(radius), printInk, 2, cv::LINE_AA);
          cv::putText(image, option, {cvRound(x-radius*.14f),cvRound(y+radius*.14f)},
              cv::FONT_HERSHEY_SIMPLEX, radius / 45.0, printInk, 1, cv::LINE_AA);
        }
        const int selected = row == 1 ? 2 : row == 2 ? 3 : row == 3 ? 1 : row == 4 ? 4 : 0;
        if (selected) {
          const cv::Scalar ink = row == 3 ? cv::Scalar(140,40,25) : row == 4 ? cv::Scalar(110,30,90) :
              lighting ? cv::Scalar(200,200,200) : cv::Scalar(24,24,24);
          cv::circle(image, {cvRound(block.xByOption.at(std::to_string(selected))),y},
              cvRound(radius*.72f), ink, cv::FILLED, cv::LINE_AA);
        }
        if (row == 5 || row == 6) {
          for (const int option : (row==5 ? std::vector<int>{1,3} : std::vector<int>{1,2,3,4})) cv::circle(image,
              {cvRound(block.xByOption.at(std::to_string(option))),y}, cvRound(radius*(row==6 && magentaPrint ? .95f : .72f)),
              cv::Scalar(24,24,24), cv::FILLED, cv::LINE_AA);
        }
      }
      if (lighting) for (int y=0;y<image.rows;++y) {
        auto* pixels=image.ptr<cv::Vec3b>(y);
        for (int x=0;x<image.cols;++x) {
          const double gain=(lighting==1 ? .42+.52*x/(image.cols-1.) : .40)*(.88+.12*y/(image.rows-1.));
          for(int c=0;c<3;++c) pixels[x][c]=cv::saturate_cast<uint8_t>(18+gain*pixels[x][c]);
        }
      }
      cv::GaussianBlur(image, image, {3,3}, .55);
      if (grayscale) cv::cvtColor(image, image, cv::COLOR_BGR2GRAY);
      if (magentaPrint) {
        std::vector<unsigned char> jpeg;
        cv::imencode(".jpg",image,jpeg,{cv::IMWRITE_JPEG_QUALITY,92});
        image=cv::imdecode(jpeg,grayscale ? cv::IMREAD_GRAYSCALE : cv::IMREAD_COLOR);
      }
      const auto result = omr::AnswerReader::read(image, tpl);
      const int expected[] = {0,2,3,1,4,0};
      for (int row = 0; row < 8; ++row) {
        const auto& q = result.questions.at(row);
        const bool highest = q.hasSelectedOption && q.selectedOptions.size() == 1 &&
          std::all_of(q.options.begin(), q.options.end(), [&](const auto& option) {
            const auto selected = std::find_if(q.options.begin(), q.options.end(), [&](const auto& value) {
              return value.option == q.selectedOption;
            });
            return selected != q.options.end() && selected->fillRatio >= option.fillRatio;
          });
        const bool correct = row == 0 || row == 7 ? q.status == "BLANK" : row == 5 || row == 6 ? q.status == "MARKED" && highest :
            q.status == "MARKED" && q.selectedOptionNumber == expected[row];
        if (!correct) {
          ok = false;
          std::cerr << "FAIL radius=" << radius << " gray=" << grayscale << " lighting="<<lighting<<" magenta="<<magentaPrint<<" row=" << row+1
                    << " status=" << q.status << " selected=" << q.selectedOption << '\n';
        }
      }
      }
     }
    }
  }
  if (ok) std::cout << "PASS: 288 known questions on black/magenta bands, faint/blue/purple ink, blanks and 2/4 marks, shadows/gradients/JPEG at three sizes in color/grayscale\n";
  return ok ? 0 : 1;
}
