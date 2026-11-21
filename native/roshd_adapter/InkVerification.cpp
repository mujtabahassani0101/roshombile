#include "InkVerification.hpp"
#include "omr/AnswerReader.hpp"
#include "utils/Timer.hpp"
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <array>
#include <cmath>
#include <set>

namespace roshd {
namespace {
bool marked(const omr::OmrAnswerQuestionResult& question) {
  return question.status=="MARKED" && question.hasSelectedOptionNumber &&
    question.selectedOptionNumber>=1 && question.selectedOptionNumber<=4;
}
bool strong(const omr::OmrAnswerQuestionResult& question) {
  if(!marked(question) || !std::isfinite(question.confidence) || question.confidence<.85)return false;
  const auto selected=std::find_if(question.options.begin(),question.options.end(),[&](const auto& option) {
    return option.hasOptionNumber && option.optionNumber==question.selectedOptionNumber;
  });
  return selected!=question.options.end() && selected->rawScore>=.55 && selected->inkDarkness>=.12;
}
bool weakVisible(const omr::OmrAnswerQuestionResult& question) {
  if(!marked(question) || question.confidence<.5)return false;
  return std::any_of(question.options.begin(),question.options.end(),[&](const auto& option) {
    return option.hasOptionNumber && option.optionNumber==question.selectedOptionNumber &&
      option.rawScore>=.35 && option.inkDarkness>=.08;
  });
}
bool originalInkVisible(const cv::Mat& image,const omr::OmrAnswerOptionScore& option,float radius) {
  // Measure surviving paper INSIDE this white circle. The surrounding printed
  // row band is not a paper reference. Channel ratios cancel local color casts.
  std::array<std::vector<int>,3> fringe;
  for(float ring:{.90f,.94f})for(int angle=0;angle<32;++angle) {
    const double radians=angle*6.283185307179586/32;
    const int x=cvRound(option.refinedX+radius*ring*std::cos(radians));
    const int y=cvRound(option.refinedY+radius*ring*std::sin(radians));
    if(x<0 || y<0 || x>=image.cols || y>=image.rows)return false;
    const auto pixel=image.at<cv::Vec3b>(y,x);
    for(int channel=0;channel<3;++channel)fringe[channel].push_back(pixel[channel]);
  }
  std::array<double,3> paper{};
  for(int channel=0;channel<3;++channel) {
    auto& samples=fringe[channel];const auto upper=samples.begin()+samples.size()*3/4;
    std::nth_element(samples.begin(),upper,samples.end());paper[channel]=*upper;
    if(paper[channel]<45)return false;
  }
  const double paperGray=.114*paper[0]+.587*paper[1]+.299*paper[2];
  double darkness=0;int chromatic=0,localizedInk=0,total=0;
  const float step=std::max(1.f,radius/12);
  for(float dy=-radius*.58f;dy<=radius*.58f;dy+=step)for(float dx=-radius*.58f;dx<=radius*.58f;dx+=step) {
    const float distance=dx*dx+dy*dy;
    if(distance<radius*radius*.38f*.38f || distance>radius*radius*.58f*.58f)continue;
    const int x=cvRound(option.refinedX+dx),y=cvRound(option.refinedY+dy);
    if(x<0 || y<0 || x>=image.cols || y>=image.rows)return false;
    const auto pixel=image.at<cv::Vec3b>(y,x);
    const double gray=.114*pixel[0]+.587*pixel[1]+.299*pixel[2];
    const double localDarkness=std::clamp((paperGray-gray)/paperGray,0.,1.);
    darkness+=localDarkness;
    if(localDarkness>=.18)++localizedInk;
    const std::array<double,3> ratios={pixel[0]/paper[0],pixel[1]/paper[1],pixel[2]/paper[2]};
    const auto [low,high]=std::minmax_element(ratios.begin(),ratios.end());
    if(*high-*low>=.15 && *low<=.80)++chromatic;
    ++total;
  }
  // Very light erased traces can be amplified into the same native class as
  // real ink. They are never promoted solely on the enhanced representation.
  // A cross/tick can occupy a small part of the annulus without the mean
  // reaching the full-fill threshold. Local dark support distinguishes that
  // surviving stroke from a uniformly light erased trace or a color cast.
  return total>=24 && (darkness/total>=.12 || chromatic>=total*.55 || localizedInk>=total*.25);
}
bool severalVisibleMarks(const omr::OmrAnswerQuestionResult& question) {
  return std::count_if(question.options.begin(),question.options.end(),[](const auto& option) {
    return option.rawScore>=.65 && option.inkDarkness>=.12;
  })>=2;
}
}

InkVerification verifyInk(omr::NormalizeResult& sheet,const omr::OmrTemplate& layout) {
  InkVerification result;
  const int rows=int(layout.registration.rowY.size());
  if(!sheet.success || !sheet.hasQuad || sheet.fallbackUsed || sheet.matchedRowsUsed!=rows ||
     sheet.homographyStrategyUsed!="all_"+std::to_string(rows)+"_registration_rows" ||
     !std::isfinite(sheet.reprojectionError) || sheet.reprojectionError>1.5 ||
     sheet.normalizedImagePath.empty())return result;
  omr::Timer timer;
  auto image=cv::imread(sheet.normalizedImagePath,cv::IMREAD_COLOR);
  if(image.empty() || image.cols!=cvRound(layout.width) || image.rows!=cvRound(layout.height)) {
    result.reason="normalized_image_unavailable";return result;
  }
  // Reuse the exact source-measured centers and its refinement safety stats.
  // No second homography, QR lookup, paper search or registration model runs.
  omr::BubbleGridRefinement refinement;
  refinement.templateRefinementUsed=sheet.templateRefinementUsed;
  refinement.answerBlockStats=sheet.answerBlockRefinementStats;
  for(const auto& question:sheet.answers.questions)for(const auto& option:question.options) {
    const auto block=std::find_if(layout.answers.blocks.begin(),layout.answers.blocks.end(),[&](const auto& block) {
      return question.questionNumber>=block.questionStart && question.questionNumber<block.questionStart+block.questionCount;
    });
    if(block==layout.answers.blocks.end())continue;
    omr::RefinedAnswerBubbleCenter center;
    center.blockId=block->id;center.questionNumber=question.questionNumber;
    center.rowIndex=question.questionNumber-block->questionStart;center.option=option.option;
    center.expected={float(option.expectedX),float(option.expectedY)};
    center.refined={float(option.refinedX),float(option.refinedY)};
    center.confidence=float(option.refinementConfidence);
    refinement.answerCenters.push_back(center);
  }
  cv::Mat minimum(image.size(),CV_8U),gamma(1,256,CV_8U);
  for(int y=0;y<image.rows;++y) {
    const auto* input=image.ptr<cv::Vec3b>(y);auto* output=minimum.ptr<unsigned char>(y);
    for(int x=0;x<image.cols;++x)output[x]=std::min({input[x][0],input[x][1],input[x][2]});
  }
  for(int value=0;value<256;++value)gamma.at<unsigned char>(value)=cv::saturate_cast<unsigned char>(255*std::pow(value/255.0,1.35));
  const int count=sheet.activeQuestionCount>0?sheet.activeQuestionCount:layout.answers.questionCount;
  std::array<omr::OmrAnswerReadResult,3> passes;
  passes[0]=omr::AnswerReader::read(minimum,layout,&refinement,count);
  // Transform and discard each representation in turn. Keep the original
  // image for independent evidence, without retaining three full-size copies.
  cv::Mat contrast;cv::LUT(image,gamma,contrast);
  passes[1]=omr::AnswerReader::read(contrast,layout,&refinement,count);contrast.release();
  cv::LUT(minimum,gamma,minimum);
  passes[2]=omr::AnswerReader::read(minimum,layout,&refinement,count);minimum.release();
  for(auto& original:sheet.answers.questions) {
    std::array<const omr::OmrAnswerQuestionResult*,3> observed{};
    for(size_t pass=0;pass<passes.size();++pass) {
      const auto found=std::find_if(passes[pass].questions.begin(),passes[pass].questions.end(),[&](const auto& question) {
        return question.questionNumber==original.questionNumber;
      });
      if(found!=passes[pass].questions.end())observed[pass]=&*found;
    }
    const omr::OmrAnswerQuestionResult* consensus=nullptr;
    int agreement=0;bool conflict=false,multiple=severalVisibleMarks(original),visible=false;
    std::set<double> visibleOptions;
    for(const auto* candidate:observed) {
      if(!candidate)continue;
      multiple=multiple || severalVisibleMarks(*candidate);
      if(original.status=="BLANK" && weakVisible(*candidate))visible=true;
      if(!strong(*candidate))continue;
      visible=true;
      visibleOptions.insert(candidate->selectedOptionNumber);
      const int votes=int(std::count_if(observed.begin(),observed.end(),[&](const auto* other) {
        return other && strong(*other) && other->selectedOptionNumber==candidate->selectedOptionNumber;
      }));
      if(votes>agreement) { agreement=votes;consensus=candidate; }
      if(marked(original) && candidate->selectedOptionNumber!=original.selectedOptionNumber)conflict=true;
    }
    conflict=conflict || visibleOptions.size()>1;
    const auto block=std::find_if(layout.answers.blocks.begin(),layout.answers.blocks.end(),[&](const auto& block) {
      return original.questionNumber>=block.questionStart && original.questionNumber<block.questionStart+block.questionCount;
    });
    // Source readers can select one of two strong marks on a striped row.
    // Surface a definite multiple only when at least two source representations
    // support each option AND its ink is independently present in the original
    // normalized photograph. Keep the source classification and scores intact.
    std::vector<int> confirmedMultiple;
    int minimumVotes=4;
    if(multiple && original.status!="MULTI" && block!=layout.answers.blocks.end()) {
      for(const auto& option:original.options) {
        if(!option.hasOptionNumber || option.optionNumber<1 || option.optionNumber>4 || std::floor(option.optionNumber)!=option.optionNumber)continue;
        const auto supports=[&](const auto& question) {
          return std::any_of(question.options.begin(),question.options.end(),[&](const auto& value) {
            return value.hasOptionNumber && value.optionNumber==option.optionNumber && value.rawScore>=.65 && value.inkDarkness>=.12;
          });
        };
        int votes=supports(original)?1:0;
        for(const auto* candidate:observed)if(candidate && supports(*candidate))++votes;
        if(votes>=2 && originalInkVisible(image,option,block->bubbleRadius)) {
          confirmedMultiple.push_back(int(option.optionNumber));minimumVotes=std::min(minimumVotes,votes);
        }
      }
    }
    if(confirmedMultiple.size()>=2) {
      std::sort(confirmedMultiple.begin(),confirmedMultiple.end());
      InkQuestionEvidence evidence;evidence.original=original;evidence.agreeingPasses=minimumVotes;
      evidence.reason="multiple_ink_confirmed";evidence.confirmedMultipleOptions=std::move(confirmedMultiple);
      result.questions.emplace(original.questionNumber,std::move(evidence));continue;
    }
    // A genuine partial/faint annulus may remain below all three native class
    // boundaries. Preserve BLANK, but do not silently certify it as empty.
    if(original.status=="BLANK" && !visible && block!=layout.answers.blocks.end()) {
      visible=std::any_of(original.options.begin(),original.options.end(),[&](const auto& option) {
        return originalInkVisible(image,option,block->bubbleRadius);
      });
    }
    std::string reason;
    if(multiple)reason="multiple_visible_marks";
    else if(conflict)reason="ink_readings_disagree";
    else if(original.status=="BLANK" && visible)reason="faint_ink_candidate";
    if(reason.empty())continue;
    InkQuestionEvidence evidence;evidence.original=original;evidence.agreeingPasses=agreement;
    // Two source readings may recover a blank only when no representation
    // finds competing marks. Every changed answer still requires confirmation.
    const auto originalOption=std::find_if(original.options.begin(),original.options.end(),[&](const auto& option) {
      return consensus && option.hasOptionNumber && option.optionNumber==consensus->selectedOptionNumber;
    });
    const bool candidateEligible=original.status=="BLANK" && !multiple && !conflict && consensus && agreement>=2;
    const bool originalContrast=candidateEligible && originalOption!=original.options.end() && block!=layout.answers.blocks.end() &&
      (originalOption->inkDarkness>=.12 || originalInkVisible(image,*originalOption,block->bubbleRadius));
    if(originalContrast) {
      original=*consensus;evidence.recovered=true;reason="ink_visibility_recovered";
    }
    evidence.reason=reason;result.questions.emplace(original.questionNumber,std::move(evidence));
  }
  result.applied=true;result.reason="source_reader_channel_consensus";result.milliseconds=timer.elapsedMs();
  return result;
}
}
